package com.cu.collective_unconscious.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

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
import com.cu.collective_unconscious.services.impl.TicketServiceImpl;

import jakarta.persistence.EntityNotFoundException;

/**
 * Unit test for {@link TicketServiceImpl} with repository + mapper mocked. Covers the pure decision
 * branches: draw guards (not-found / completed / access code / maxRounds), the RobinRound join rule
 * (already a participant), FreeTurn's "no two in a row" rule, and the advance branch (advanceWork).
 * The write entry points (drawTicket / advanceWork) lock the aggregate root via
 * {@code findByIdForUpdate}; the read entry points (getCurrentTurn / listTickets) are pure reads and
 * no longer self-heal timeouts — that is advanceWork()'s / the scheduler's job.
 *
 * <p>The RobinRound round-based generation ({@code check()}), full A→B→A rotation, single-user
 * waiting, join-adds-to-authors and scheduler timeout self-heal are stateful multi-query flows,
 * pinned end-to-end against real Postgres in {@code TicketFlowIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class TicketServiceImplTest {

  @Mock TicketRepository ticketRepository;
  @Mock WorkRepository workRepository;
  @Mock TicketMapper ticketMapper;
  @InjectMocks TicketServiceImpl service;

  private User user(String name) {
    return User.builder().id(UUID.randomUUID()).username(name).build();
  }

  private Work work(TurnStyle style, boolean completed, String accessCode) {
    return Work.builder()
        .id(UUID.randomUUID())
        .title("W").preface("p")
        .turnStyle(style)
        .completed(completed)
        .accessCode(accessCode)
        .ticketTtlSeconds(3600)
        .build();
  }

  private Ticket ticket(Work work, User holder, long number, TicketStatus status) {
    return Ticket.builder()
        .id(UUID.randomUUID())
        .work(work).holder(holder).ticketNumber(number).status(status)
        .build();
  }

  // Makes advance() a no-op: no ACTIVE, no WAITING head. (RobinRound's check() then no-ops too,
  // since findHoldersInJoinOrder defaults to an empty list -> empty ring.)
  private void stubEmptyQueue(Work work) {
    when(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .thenReturn(Optional.empty());
    when(ticketRepository.findFirstByWorkAndStatusOrderByTicketNumberAsc(work, TicketStatus.WAITING))
        .thenReturn(Optional.empty());
  }

  // --- drawTicket: guard branches --------------------------------------------

  @Test
  void drawTicket_unknownWork_throwsEntityNotFound() {
    UUID workId = UUID.randomUUID();
    when(workRepository.findByIdForUpdate(workId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.drawTicket(workId, user("a"), null))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void drawTicket_workCompleted_throwsIllegalState_andSavesNothing() {
    Work work = work(TurnStyle.ROBINROUND, true, null);
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));

    assertThatThrownBy(() -> service.drawTicket(work.getId(), user("a"), null))
        .isInstanceOf(IllegalStateException.class);
    verify(ticketRepository, never()).save(any());
  }

  @Test
  void drawTicket_accessCodeRequiredButMissing_throwsAccessDenied() {
    Work work = work(TurnStyle.ROBINROUND, false, "secret");
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));

    assertThatThrownBy(() -> service.drawTicket(work.getId(), user("a"), null))
        .isInstanceOf(AccessDeniedException.class);
    verify(ticketRepository, never()).save(any());
  }

  @Test
  void drawTicket_accessCodeWrong_throwsAccessDenied() {
    Work work = work(TurnStyle.ROBINROUND, false, "secret");
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));

    assertThatThrownBy(() -> service.drawTicket(work.getId(), user("a"), "nope"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void drawTicket_maxRoundsReached_throwsIllegalState_andSavesNothing() {
    Work work = Work.builder().id(UUID.randomUUID()).title("W").preface("p")
        .turnStyle(TurnStyle.FREETURN).ticketTtlSeconds(3600).maxRounds(2).build();
    User u = user("a");
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    stubEmptyQueue(work);
    when(ticketRepository.countByWorkAndStatusIn(eq(work), any())).thenReturn(2L); // already at cap

    assertThatThrownBy(() -> service.drawTicket(work.getId(), u, null))
        .isInstanceOf(IllegalStateException.class);
    verify(ticketRepository, never()).save(any());
  }

  @Test
  void drawTicket_maxParticipantsReached_brandNewUser_throwsIllegalState_andSavesNothing() {
    Work work = Work.builder().id(UUID.randomUUID()).title("W").preface("p")
        .turnStyle(TurnStyle.ROBINROUND).ticketTtlSeconds(3600).maxParticipants(2).build();
    User u = user("newbie");
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    stubEmptyQueue(work);
    when(ticketRepository.existsByWorkAndHolder(work, u)).thenReturn(false); // never drawn here
    when(ticketRepository.countDistinctHoldersByWork(work)).thenReturn(2L);  // already at the cap

    assertThatThrownBy(() -> service.drawTicket(work.getId(), u, null))
        .isInstanceOf(IllegalStateException.class);
    verify(ticketRepository, never()).save(any());
  }

  @Test
  void drawTicket_robinRound_alreadyInAuthors_throwsIllegalState_andSavesNothing() {
    Work work = work(TurnStyle.ROBINROUND, false, null);
    User u = user("a");
    work.getAuthors().add(u); // already joined the rotation
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    stubEmptyQueue(work);

    assertThatThrownBy(() -> service.drawTicket(work.getId(), u, null))
        .isInstanceOf(IllegalStateException.class);
    verify(ticketRepository, never()).save(any());
  }

  @Test
  void drawTicket_freeTurn_callerHoldsMostRecentTicket_throwsIllegalState() {
    Work work = work(TurnStyle.FREETURN, false, null);
    User u = user("a");
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    stubEmptyQueue(work);
    when(ticketRepository.findFirstByWorkAndStatusInOrderByTicketNumberDesc(eq(work), any()))
        .thenReturn(Optional.of(ticket(work, u, 5, TicketStatus.CONSUMED)));

    assertThatThrownBy(() -> service.drawTicket(work.getId(), u, null))
        .isInstanceOf(IllegalStateException.class);
    verify(ticketRepository, never()).save(any());
  }

  @Test
  void drawTicket_freeTurn_someoneElseHoldsMostRecent_savesWaitingWithNextNumber() {
    Work work = work(TurnStyle.FREETURN, false, null);
    User me = user("me");
    User other = user("other");
    Ticket active = ticket(work, other, 1, TicketStatus.ACTIVE);
    active.setExpiresAt(LocalDateTime.now().plusHours(1)); // not expired
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    when(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .thenReturn(Optional.of(active)); // advance sees a live ACTIVE -> no activation
    when(ticketRepository.findFirstByWorkAndStatusInOrderByTicketNumberDesc(eq(work), any()))
        .thenReturn(Optional.of(active)); // most recent is other, not me -> eligible
    when(ticketRepository.findMaxTicketNumber(work)).thenReturn(1L);
    ArgumentCaptor<Ticket> captor = ArgumentCaptor.forClass(Ticket.class);
    when(ticketRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));
    when(ticketMapper.toDto(any(Ticket.class))).thenReturn(TicketDto.builder().build());

    service.drawTicket(work.getId(), me, null);

    Ticket saved = captor.getValue();
    assertThat(saved.getHolder()).isEqualTo(me);
    assertThat(saved.getTicketNumber()).isEqualTo(2);
    assertThat(saved.getStatus()).isEqualTo(TicketStatus.WAITING); // not activated: other is live
  }

  // --- getCurrentTurn (pure read: no advance) --------------------------------

  @Test
  void getCurrentTurn_noActive_emptyQueue_notMyTurn() {
    Work work = work(TurnStyle.ROBINROUND, false, null);
    User u = user("a");
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    when(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .thenReturn(Optional.empty());

    CurrentTurnDto dto = service.getCurrentTurn(work.getId(), u);

    assertThat(dto.getWorkId()).isEqualTo(work.getId());
    assertThat(dto.isItIsMyTurn()).isFalse();
    assertThat(dto.getActiveHolderId()).isNull();
  }

  @Test
  void getCurrentTurn_activeHeldByCaller_itIsMyTurnTrue() {
    Work work = work(TurnStyle.ROBINROUND, false, null);
    User u = user("a");
    Ticket active = ticket(work, u, 4, TicketStatus.ACTIVE);
    active.setExpiresAt(LocalDateTime.now().plusHours(1));
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    when(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .thenReturn(Optional.of(active));

    CurrentTurnDto dto = service.getCurrentTurn(work.getId(), u);

    assertThat(dto.isItIsMyTurn()).isTrue();
    assertThat(dto.getActiveHolderId()).isEqualTo(u.getId());
    assertThat(dto.getActiveTicketNumber()).isEqualTo(4);
    assertThat(dto.getExpiresAt()).isEqualTo(active.getExpiresAt());
  }

  @Test
  void getCurrentTurn_activeHeldByOther_itIsMyTurnFalse() {
    Work work = work(TurnStyle.ROBINROUND, false, null);
    User me = user("me");
    User other = user("other");
    Ticket active = ticket(work, other, 4, TicketStatus.ACTIVE);
    active.setExpiresAt(LocalDateTime.now().plusHours(1));
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    when(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .thenReturn(Optional.of(active));

    CurrentTurnDto dto = service.getCurrentTurn(work.getId(), me);

    assertThat(dto.isItIsMyTurn()).isFalse();
    assertThat(dto.getActiveHolderId()).isEqualTo(other.getId());
  }

  // Reads no longer self-heal: advanceWork() does the expiry + activation, then the read reflects it.
  @Test
  void advanceWorkThenGetCurrentTurn_expiredIsRotated_freeTurn() {
    Work work = work(TurnStyle.FREETURN, false, null); // FreeTurn: no generation
    User a = user("a");
    User b = user("b");
    Ticket expired = ticket(work, a, 1, TicketStatus.ACTIVE);
    expired.setExpiresAt(LocalDateTime.now().minusMinutes(5)); // past
    Ticket next = ticket(work, b, 2, TicketStatus.WAITING);
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work)); // advanceWork locks
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));          // getCurrentTurn reads
    when(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .thenReturn(Optional.of(expired))  // advanceWork sees the timed-out ACTIVE
        .thenReturn(Optional.of(next));    // getCurrentTurn then sees the newly ACTIVE
    when(ticketRepository.findFirstByWorkAndStatusOrderByTicketNumberAsc(work, TicketStatus.WAITING))
        .thenReturn(Optional.of(next));

    service.advanceWork(work.getId());
    CurrentTurnDto dto = service.getCurrentTurn(work.getId(), b);

    assertThat(expired.getStatus()).isEqualTo(TicketStatus.EXPIRED);
    assertThat(next.getStatus()).isEqualTo(TicketStatus.ACTIVE);
    assertThat(next.getActivatedAt()).isNotNull();
    assertThat(next.getExpiresAt()).isEqualTo(next.getActivatedAt().plusSeconds(3600));
    assertThat(dto.isItIsMyTurn()).isTrue(); // b now holds ACTIVE
  }

  @Test
  void getCurrentTurn_unknownWork_throwsEntityNotFound() {
    UUID workId = UUID.randomUUID();
    when(workRepository.findById(workId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getCurrentTurn(workId, user("a")))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // --- listTickets (pure read: no advance) -----------------------------------

  @Test
  void listTickets_mapsWholeQueueIncludingHistory() {
    Work work = work(TurnStyle.FREETURN, false, null);
    User u = user("a");
    Ticket t1 = ticket(work, u, 1, TicketStatus.CONSUMED); // history is included
    Ticket t2 = ticket(work, u, 2, TicketStatus.ACTIVE);
    t2.setExpiresAt(LocalDateTime.now().plusHours(1));
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    when(ticketRepository.findByWorkWithHolderOrderByTicketNumberAsc(work))
        .thenReturn(List.of(t1, t2));
    when(ticketMapper.toDto(t1)).thenReturn(TicketDto.builder().ticketNumber(1).build());
    when(ticketMapper.toDto(t2)).thenReturn(TicketDto.builder().ticketNumber(2).build());

    List<TicketDto> list = service.listTickets(work.getId());

    assertThat(list).extracting(TicketDto::getTicketNumber).containsExactly(1L, 2L);
  }

  @Test
  void listTickets_unknownWork_throwsEntityNotFound() {
    UUID workId = UUID.randomUUID();
    when(workRepository.findById(workId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.listTickets(workId))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // --- advanceWork (scheduler entry) -----------------------------------------

  @Test
  void advanceWork_unknownWork_throwsEntityNotFound() {
    UUID workId = UUID.randomUUID();
    when(workRepository.findByIdForUpdate(workId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.advanceWork(workId))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void advanceWork_expiresActiveAndActivatesNext_freeTurn() {
    Work work = work(TurnStyle.FREETURN, false, null);
    User a = user("a");
    User b = user("b");
    Ticket expired = ticket(work, a, 1, TicketStatus.ACTIVE);
    expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    Ticket next = ticket(work, b, 2, TicketStatus.WAITING);
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    when(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .thenReturn(Optional.of(expired));
    when(ticketRepository.findFirstByWorkAndStatusOrderByTicketNumberAsc(work, TicketStatus.WAITING))
        .thenReturn(Optional.of(next));

    service.advanceWork(work.getId());

    assertThat(expired.getStatus()).isEqualTo(TicketStatus.EXPIRED);
    assertThat(next.getStatus()).isEqualTo(TicketStatus.ACTIVE);
  }
}
