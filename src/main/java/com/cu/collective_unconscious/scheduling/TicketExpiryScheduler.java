package com.cu.collective_unconscious.scheduling;

import java.time.LocalDateTime;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.services.TicketService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Timeout backstop, first path (plan §6): periodically expire ACTIVE tickets whose TTL has passed
 * and hand the turn to the next person — so a queue keeps moving even when nobody is reading it.
 * The second path (read-time {@code advance} in the service) covers the &le;1 scan-interval gap and
 * survives restarts. Scheduling is only wired on in non-test profiles (see {@code SchedulingConfig}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TicketExpiryScheduler {

  private final TicketRepository ticketRepository;
  private final TicketService ticketService;

  @Scheduled(fixedDelayString = "${cu.ticket.expiry-scan-ms:60000}")
  public void sweepExpiredTickets() {
    LocalDateTime now = LocalDateTime.now();
    // getWork().getId() reads the FK off the lazy proxy without initialising it — safe with no open
    // session. distinct() is defensive: the single-ACTIVE invariant means one work yields one row.
    ticketRepository.findByStatusAndExpiresAtBefore(TicketStatus.ACTIVE, now).stream()
        .map(ticket -> ticket.getWork().getId())
        .distinct()
        .forEach(workId -> {
          try {
            ticketService.advanceWork(workId);
          } catch (RuntimeException ex) {
            // One work's failure (e.g. optimistic-lock clash with a concurrent submit) must not
            // abort the whole sweep; the next scan retries.
            log.warn("Ticket expiry sweep failed to advance work {}", workId, ex);
          }
        });
  }
}
