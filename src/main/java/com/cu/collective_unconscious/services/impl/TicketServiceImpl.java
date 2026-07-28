package com.cu.collective_unconscious.services.impl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.dtos.CurrentTurnDto;
import com.cu.collective_unconscious.dtos.TicketDto;
import com.cu.collective_unconscious.mappers.TicketMapper;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.services.TicketService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

/**
 * Ticket state machine (see docs/设计文档.md §2.5).
 *
 * <p>RobinRound is a system-managed <em>round-based</em> queue: you draw once to <em>join</em>
 * (which adds you to {@code work.authors}); thereafter the system keeps one full upcoming round of
 * WAITING tickets queued ahead of the ACTIVE one ({@link #check}), cycling participants in join
 * order. Nobody writes twice in a row, and a lone participant simply waits. FreeTurn is manual:
 * draw each turn, guarded only by "the most recent ticket isn't you".
 */
@Service
@RequiredArgsConstructor
public class TicketServiceImpl implements TicketService {

  // "Counted" statuses: a turn you hold or already wrote (queued/writing/written). EXPIRED/SKIPPED
  // are forfeited and don't count. Used by FreeTurn's "no two in a row" rule and the maxRounds cap.
  private static final List<TicketStatus> COUNTED =
      List.of(TicketStatus.WAITING, TicketStatus.ACTIVE, TicketStatus.CONSUMED);

  private final TicketRepository ticketRepository;
  private final WorkRepository workRepository;
  private final TicketMapper ticketMapper;

  @Transactional
  @Override
  public TicketDto drawTicket(UUID workId, User user, String accessCode) {
    // Lock the aggregate root first: all ticket-state ops on this work serialize on this row.
    Work work = workRepository.findByIdForUpdate(workId)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + workId));

    if (work.isCompleted()) {
      throw new IllegalStateException("Work is already completed; no new tickets may be drawn");
    }
    if (work.getAccessCode() != null && !work.getAccessCode().equals(accessCode)) {
      throw new AccessDeniedException("Invalid or missing access code");
    }

    advance(work); // clear timeouts first, so eligibility reasons over fresh state

    if (atMaxRounds(work)) {
      throw new IllegalStateException("Work has reached its max rounds; no new tickets may be drawn");
    }

    // maxParticipants cap (null = unlimited): only a brand-new participant (someone who has never
    // drawn here) is rejected once the distinct-participant count is at the limit; people already in
    // are exempt.
    if (work.getMaxParticipants() != null
        && !ticketRepository.existsByWorkAndHolder(work, user)
        && ticketRepository.countDistinctHoldersByWork(work) >= work.getMaxParticipants()) {
      throw new IllegalStateException(
          "Work has reached its participant limit (" + work.getMaxParticipants() + ")");
    }

    if (work.getTurnStyle() == TurnStyle.ROBINROUND) {
      // Draw == join the ring. You're managed automatically after joining, so you can't draw again
      // while a participant. Membership lives in the mutable work.authors (removal-aware).
      if (work.getAuthors().contains(user)) {
        throw new IllegalStateException("You are already in this work's rotation");
      }
      work.getAuthors().add(user);
    } else { // FREETURN
      // No two turns in a row: the most recent WAITING/ACTIVE/CONSUMED ticket must not be yours.
      Ticket latest = ticketRepository
          .findFirstByWorkAndStatusInOrderByTicketNumberDesc(work, COUNTED).orElse(null);
      if (latest != null && latest.getHolder().equals(user)) {
        throw new IllegalStateException("You can't take two turns in a row; wait for another participant");
      }
    }

    Ticket ticket = createWaiting(work, user);
    advance(work); // activate it if the queue was idle, and (RobinRound) top up the lookahead

    return ticketMapper.toDto(ticket);
  }

  // Pure read， a just-expired ticket may show stale for ≤1 sweep (≤60s).
  @Transactional(readOnly = true)
  @Override
  public List<TicketDto> listTickets(UUID workId) {
    Work work = workRepository.findById(workId)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + workId));
    return ticketRepository.findByWorkWithHolderOrderByTicketNumberAsc(work).stream()
        .map(ticketMapper::toDto)
        .toList();
  }

  // Pure read: no read-time advance (see listTickets). A just-expired ACTIVE may still show here
  // until the scheduler advances it (≤1 sweep).
  @Transactional(readOnly = true)
  @Override
  public CurrentTurnDto getCurrentTurn(UUID workId, User user) {
    Work work = workRepository.findById(workId)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + workId));
    Ticket active =
        ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE).orElse(null);
    boolean myTurn = active != null && active.getHolder().equals(user);
    return CurrentTurnDto.builder()
        .workId(work.getId())
        .activeHolderId(active == null ? null : active.getHolder().getId())
        .activeHolderUsername(active == null ? null : active.getHolder().getUsername())
        .activeTicketNumber(active == null ? null : active.getTicketNumber())
        .expiresAt(active == null ? null : active.getExpiresAt())
        .itIsMyTurn(myTurn)
        .build();
  }

  @Transactional
  @Override
  public void consumeActiveTicketForSubmit(Work work, User user, Contribution contribution) {
    Ticket active =
        ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE).orElse(null);
    LocalDateTime now = LocalDateTime.now();
    boolean held = active != null
        && active.getHolder().equals(user)
        && (active.getExpiresAt() == null || !now.isAfter(active.getExpiresAt()));
    if (!held) {
      throw new IllegalStateException(
          "It is not your turn: you do not hold an active, unexpired ticket for this work");
    }
    active.setStatus(TicketStatus.CONSUMED);
    active.setContribution(contribution);
    // Hand the turn onward: activate the next WAITING and (RobinRound) top up the lookahead.
    advance(work);
  }

  @Transactional
  @Override
  public void advanceWork(UUID workId) {
    Work work = workRepository.findByIdForUpdate(workId)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + workId));
    advance(work);
  }

  // --- the engine -------------------------------------------------------------

  // Expire the timed-out ACTIVE (if any), activate the next WAITING, then — for RobinRound — top up
  // the one-round lookahead. FreeTurn never generates ahead.
  private void advance(Work work) {
    LocalDateTime now = LocalDateTime.now();
    Ticket active =
        ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE).orElse(null);

    if (active != null && active.getExpiresAt() != null && now.isAfter(active.getExpiresAt())) {
      active.setStatus(TicketStatus.EXPIRED);
      active = null;
    }

    if (active == null) {
      ticketRepository.findFirstByWorkAndStatusOrderByTicketNumberAsc(work, TicketStatus.WAITING)
          .ifPresent(next -> {
            next.setStatus(TicketStatus.ACTIVE);
            next.setActivatedAt(now);
            next.setExpiresAt(now.plusSeconds(work.getTicketTtlSeconds()));
          });
    }

    if (work.getTurnStyle() == TurnStyle.ROBINROUND) {
      check(work);
    }
  }

  // RobinRound generation: keep exactly N (= #participants) WAITING queued ahead of the ACTIVE, in
  // round-robin order (each new one is the successor of the newest ticket's holder). Self-locks when
  // N == 1 (the lone participant would follow themselves) → nothing queued → the work waits. Never
  // exceeds maxRounds. Also runs after a skip (Phase 5).
  private void check(Work work) {
    List<User> ring = participantRing(work);
    int n = ring.size();
    if (n == 0) {
      return;
    }
    while (ticketRepository.countByWorkAndStatus(work, TicketStatus.WAITING) < n) {
      if (atMaxRounds(work)) {
        break;
      }
      Ticket last = ticketRepository.findFirstByWorkOrderByTicketNumberDesc(work).orElse(null);
      if (last == null) {
        break;
      }
      User next = successor(ring, last.getHolder());
      if (next.equals(last.getHolder())) {
        break; // self-lock: only one participant, they'd follow themselves
      }
      createWaiting(work, next);
    }
  }

  // The RobinRound ring: holders in join order, filtered to the current authors (so a removed
  // author's lingering tickets don't keep them in the rotation).
  private List<User> participantRing(Work work) {
    Set<User> authors = work.getAuthors();
    return ticketRepository.findHoldersInJoinOrder(work).stream()
        .filter(authors::contains)
        .toList();
  }

  private User successor(List<User> ring, User holder) {
    int i = ring.indexOf(holder);
    if (i < 0) {
      return ring.get(0); // holder no longer in the ring (removed) — restart from the front
    }
    return ring.get((i + 1) % ring.size());
  }

  private Ticket createWaiting(Work work, User holder) {
    return ticketRepository.save(Ticket.builder()
        .work(work)
        .holder(holder)
        .ticketNumber(nextTicketNumber(work))
        .status(TicketStatus.WAITING)
        .build());
  }

  // maxRounds caps total CONSUMED+ACTIVE+WAITING tickets (SKIPPED/EXPIRED don't count). Null = no cap.
  private boolean atMaxRounds(Work work) {
    return work.getMaxRounds() != null
        && ticketRepository.countByWorkAndStatusIn(work, COUNTED) >= work.getMaxRounds();
  }

  private long nextTicketNumber(Work work) {
    Long max = ticketRepository.findMaxTicketNumber(work);
    return (max == null ? 0L : max) + 1L;
  }
}
