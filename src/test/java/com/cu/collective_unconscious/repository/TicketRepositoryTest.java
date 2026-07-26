package com.cu.collective_unconscious.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;

/**
 * Slice test for {@link TicketRepository} against real Postgres. Pins the six queries the ticket
 * state machine relies on: "am I already queued?" (RobinRound draw rule), "who is the tail
 * ticket?" (FreeTurn draw rule + re-enqueue self-lock guard), "which ticket is ACTIVE?", "which
 * WAITING is next to activate?", "what is the next ticket_number?", and "which ACTIVE tickets have
 * expired?" (scheduler sweep).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TicketRepositoryTest {

  @Autowired TicketRepository ticketRepository;
  @Autowired TestEntityManager em;

  private User newUser() {
    return em.persist(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());
  }

  private Work newWork(User author) {
    return em.persist(Work.builder()
        .title("A Work")
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(author)
        .build());
  }

  private Ticket newTicket(Work work, User holder, long number, TicketStatus status) {
    return em.persist(Ticket.builder()
        .work(work)
        .holder(holder)
        .ticketNumber(number)
        .status(status)
        .build());
  }

  private Ticket newActiveTicket(Work work, User holder, long number, LocalDateTime expiresAt) {
    return em.persist(Ticket.builder()
        .work(work)
        .holder(holder)
        .ticketNumber(number)
        .status(TicketStatus.ACTIVE)
        .activatedAt(expiresAt.minusHours(1))
        .expiresAt(expiresAt)
        .build());
  }

  // --- findHoldersInJoinOrder (RobinRound ring order) -------------------------

  @Test
  void findHoldersInJoinOrder_returnsDistinctHolders_orderedByFirstTicketNumber() {
    User author = newUser();
    User a = newUser();
    User b = newUser();
    User c = newUser();
    Work work = newWork(author);
    newTicket(work, b, 1, TicketStatus.CONSUMED);
    newTicket(work, a, 2, TicketStatus.ACTIVE);
    newTicket(work, c, 3, TicketStatus.WAITING);
    newTicket(work, b, 4, TicketStatus.WAITING); // b again -> b keeps its first-ticket (1) position
    em.flush();
    em.clear();

    assertThat(ticketRepository.findHoldersInJoinOrder(work))
        .containsExactly(b, a, c); // by min ticket_number: b#1, a#2, c#3
  }

  @Test
  void findHoldersInJoinOrder_isScopedToWork_andEmptyWhenNoTickets() {
    User author = newUser();
    Work work = newWork(author);
    Work otherWork = newWork(author);
    newTicket(otherWork, author, 1, TicketStatus.WAITING);
    em.flush();
    em.clear();

    assertThat(ticketRepository.findHoldersInJoinOrder(work)).isEmpty();
  }

  // --- findFirstByWorkOrderByTicketNumberDesc (newest ticket, any status) -----

  @Test
  void findFirstByWorkOrderByTicketNumberDesc_returnsHighestNumberedTicket_anyStatus() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    newTicket(work, a, 1, TicketStatus.CONSUMED);
    Ticket newest = newTicket(work, a, 2, TicketStatus.EXPIRED); // highest, even though EXPIRED
    em.flush();
    em.clear();

    assertThat(ticketRepository.findFirstByWorkOrderByTicketNumberDesc(work))
        .get().extracting(Ticket::getId).isEqualTo(newest.getId());
  }

  // --- countByWorkAndStatus / countByWorkAndStatusIn --------------------------

  @Test
  void countByWorkAndStatus_countsOnlyThatStatusForThatWork() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    newTicket(work, a, 1, TicketStatus.WAITING);
    newTicket(work, a, 2, TicketStatus.WAITING);
    newTicket(work, a, 3, TicketStatus.ACTIVE);
    em.flush();
    em.clear();

    assertThat(ticketRepository.countByWorkAndStatus(work, TicketStatus.WAITING)).isEqualTo(2);
  }

  @Test
  void countByWorkAndStatusIn_countsAcrossGivenStatuses() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    newTicket(work, a, 1, TicketStatus.CONSUMED);
    newTicket(work, a, 2, TicketStatus.ACTIVE);
    newTicket(work, a, 3, TicketStatus.WAITING);
    newTicket(work, a, 4, TicketStatus.EXPIRED); // excluded
    em.flush();
    em.clear();

    assertThat(ticketRepository.countByWorkAndStatusIn(work,
        List.of(TicketStatus.CONSUMED, TicketStatus.ACTIVE, TicketStatus.WAITING))).isEqualTo(3);
  }

  // --- findFirstByWorkAndStatusInOrderByTicketNumberDesc (FreeTurn tail) -------

  @Test
  void findTail_returnsHighestNumberedWaitingOrActiveTicket() {
    User author = newUser();
    User a = newUser();
    User b = newUser();
    Work work = newWork(author);
    newTicket(work, a, 1, TicketStatus.CONSUMED); // finished, ignored
    newTicket(work, a, 2, TicketStatus.ACTIVE);
    Ticket tail = newTicket(work, b, 3, TicketStatus.WAITING);
    em.flush();
    em.clear();

    Optional<Ticket> found = ticketRepository.findFirstByWorkAndStatusInOrderByTicketNumberDesc(
        work, List.of(TicketStatus.WAITING, TicketStatus.ACTIVE));

    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(tail.getId());
  }

  @Test
  void findTail_returnsEmptyWhenNoOpenTickets() {
    User author = newUser();
    Work work = newWork(author);
    em.flush();
    em.clear();

    assertThat(ticketRepository.findFirstByWorkAndStatusInOrderByTicketNumberDesc(
        work, List.of(TicketStatus.WAITING, TicketStatus.ACTIVE))).isEmpty();
  }

  // --- findFirstByWorkAndStatus (the single ACTIVE) ---------------------------

  @Test
  void findFirstByWorkAndStatus_returnsTheActiveTicket() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    newTicket(work, a, 1, TicketStatus.CONSUMED);
    Ticket active = newActiveTicket(work, a, 2, LocalDateTime.now().plusHours(1));
    em.flush();
    em.clear();

    Optional<Ticket> found = ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE);

    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(active.getId());
  }

  // --- findFirstByWorkAndStatusOrderByTicketNumberAsc (queue head WAITING) ----

  @Test
  void findFirstWaiting_returnsLowestNumberedWaiting() {
    User author = newUser();
    User a = newUser();
    User b = newUser();
    Work work = newWork(author);
    Ticket head = newTicket(work, a, 2, TicketStatus.WAITING);
    newTicket(work, b, 3, TicketStatus.WAITING);
    em.flush();
    em.clear();

    Optional<Ticket> found = ticketRepository.findFirstByWorkAndStatusOrderByTicketNumberAsc(
        work, TicketStatus.WAITING);

    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(head.getId());
  }

  // --- findMaxTicketNumber ----------------------------------------------------

  @Test
  void findMaxTicketNumber_returnsNullWhenWorkHasNoTickets() {
    User author = newUser();
    Work work = newWork(author);
    em.flush();
    em.clear();

    assertThat(ticketRepository.findMaxTicketNumber(work)).isNull();
  }

  @Test
  void findMaxTicketNumber_returnsHighestAcrossAllStatuses() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    newTicket(work, a, 1, TicketStatus.CONSUMED);
    newTicket(work, a, 2, TicketStatus.EXPIRED);
    newTicket(work, a, 3, TicketStatus.WAITING);
    em.flush();
    em.clear();

    assertThat(ticketRepository.findMaxTicketNumber(work)).isEqualTo(3L);
  }

  @Test
  void findMaxTicketNumber_isScopedToTheGivenWork() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    Work otherWork = newWork(author);
    newTicket(work, a, 1, TicketStatus.WAITING);
    newTicket(otherWork, a, 9, TicketStatus.WAITING);
    em.flush();
    em.clear();

    assertThat(ticketRepository.findMaxTicketNumber(work)).isEqualTo(1L);
  }

  // --- findByWorkWithHolderOrderByTicketNumberAsc (queue list, full history) --

  @Test
  void findQueue_returnsAllStatusesOrderedByNumber_andFetchesHolder() {
    User author = newUser();
    User a = newUser();
    User b = newUser();
    Work work = newWork(author);
    newTicket(work, a, 2, TicketStatus.ACTIVE);
    newTicket(work, a, 1, TicketStatus.CONSUMED);
    newTicket(work, b, 3, TicketStatus.WAITING);
    em.flush();
    em.clear();

    List<Ticket> queue = ticketRepository.findByWorkWithHolderOrderByTicketNumberAsc(work);

    assertThat(queue).extracting(Ticket::getTicketNumber).containsExactly(1L, 2L, 3L);
    // Session cleared: reading the JOIN FETCHed holder must not raise LazyInitializationException.
    assertThat(queue.get(0).getHolder().getUsername()).isEqualTo(a.getUsername());
  }

  @Test
  void findQueue_isScopedToTheGivenWork() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    Work otherWork = newWork(author);
    newTicket(work, a, 1, TicketStatus.WAITING);
    newTicket(otherWork, a, 1, TicketStatus.WAITING);
    em.flush();
    em.clear();

    assertThat(ticketRepository.findByWorkWithHolderOrderByTicketNumberAsc(work)).hasSize(1);
  }

  // --- findByStatusAndExpiresAtBefore (scheduler sweep) -----------------------

  @Test
  void findExpiredActive_returnsActiveTicketsPastTheirExpiry() {
    User author = newUser();
    User a = newUser();
    User b = newUser();
    Work work = newWork(author);
    Ticket stale = newActiveTicket(work, a, 1, LocalDateTime.now().minusMinutes(5)); // expired
    newActiveTicket(work, b, 2, LocalDateTime.now().plusHours(1));                    // still valid
    em.flush();
    em.clear();

    List<Ticket> expired = ticketRepository.findByStatusAndExpiresAtBefore(
        TicketStatus.ACTIVE, LocalDateTime.now());

    assertThat(expired).extracting(Ticket::getId).containsExactly(stale.getId());
  }

  @Test
  void findExpiredActive_ignoresNonActiveTickets() {
    User author = newUser();
    User a = newUser();
    Work work = newWork(author);
    // A WAITING ticket with a past expiresAt would be nonsense, but even if present it must not
    // be swept: only ACTIVE tickets consume TTL.
    Ticket waiting = Ticket.builder()
        .work(work).holder(a).ticketNumber(1)
        .status(TicketStatus.WAITING)
        .expiresAt(LocalDateTime.now().minusMinutes(5))
        .build();
    em.persist(waiting);
    em.flush();
    em.clear();

    assertThat(ticketRepository.findByStatusAndExpiresAtBefore(
        TicketStatus.ACTIVE, LocalDateTime.now())).isEmpty();
  }
}
