package com.cu.collective_unconscious.scheduling;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.services.TicketService;

/**
 * Unit test for {@link TicketExpiryScheduler}'s sweep logic (the @Scheduled method is called
 * directly, not via real timing). It finds every timed-out ACTIVE ticket and asks the service to
 * advance each affected work exactly once — the "background" timeout backstop (first path).
 */
@ExtendWith(MockitoExtension.class)
class TicketExpirySchedulerTest {

  @Mock TicketRepository ticketRepository;
  @Mock TicketService ticketService;
  @InjectMocks TicketExpiryScheduler scheduler;

  private Ticket expiredActiveOn(Work work) {
    return Ticket.builder()
        .id(UUID.randomUUID())
        .work(work)
        .holder(User.builder().id(UUID.randomUUID()).build())
        .ticketNumber(1)
        .status(TicketStatus.ACTIVE)
        .expiresAt(LocalDateTime.now().minusMinutes(5))
        .build();
  }

  @Test
  void sweep_advancesEachWorkThatHasAnExpiredActiveTicket() {
    Work workA = Work.builder().id(UUID.randomUUID()).build();
    Work workB = Work.builder().id(UUID.randomUUID()).build();
    when(ticketRepository.findByStatusAndExpiresAtBefore(eq(TicketStatus.ACTIVE), any()))
        .thenReturn(List.of(expiredActiveOn(workA), expiredActiveOn(workB)));

    scheduler.sweepExpiredTickets();

    verify(ticketService).advanceWork(workA.getId());
    verify(ticketService).advanceWork(workB.getId());
    verifyNoMoreInteractions(ticketService);
  }

  @Test
  void sweep_deduplicatesByWork_whenMultipleExpiredTicketsShareAWork() {
    // Defensive: the single-ACTIVE invariant means this shouldn't happen, but distinct() guarantees
    // we never advance the same work twice per sweep.
    Work work = Work.builder().id(UUID.randomUUID()).build();
    when(ticketRepository.findByStatusAndExpiresAtBefore(eq(TicketStatus.ACTIVE), any()))
        .thenReturn(List.of(expiredActiveOn(work), expiredActiveOn(work)));

    scheduler.sweepExpiredTickets();

    verify(ticketService).advanceWork(work.getId());
    verifyNoMoreInteractions(ticketService);
  }

  @Test
  void sweep_doesNothing_whenNoExpiredTickets() {
    when(ticketRepository.findByStatusAndExpiresAtBefore(eq(TicketStatus.ACTIVE), any()))
        .thenReturn(List.of());

    scheduler.sweepExpiredTickets();

    verifyNoInteractions(ticketService);
  }
}
