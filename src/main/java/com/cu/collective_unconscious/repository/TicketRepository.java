package com.cu.collective_unconscious.repository;

import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TicketRepository extends JpaRepository<Ticket, UUID> {

  // FreeTurn draw rule ("no two in a row"): the highest-numbered ticket among the given statuses.
  Optional<Ticket> findFirstByWorkAndStatusInOrderByTicketNumberDesc(
      Work work, Collection<TicketStatus> statuses);

  // Distinct ticket-holders ordered by their first ticket_number (join order). The RobinRound ring
  // is this intersected in the service with the mutable work.authors, so a Phase-5-removed author
  // (whose old tickets persist) drops out of the rotation while the order stays stable.
  @Query("SELECT t.holder FROM Ticket t WHERE t.work = :work "
      + "GROUP BY t.holder ORDER BY MIN(t.ticketNumber) ASC")
  List<User> findHoldersInJoinOrder(@Param("work") Work work);

  // The highest-numbered ticket of any status — its holder is who round-robin continues from.
  Optional<Ticket> findFirstByWorkOrderByTicketNumberDesc(Work work);

  // maxParticipants cap: has this user ever drawn here (already a participant)?
  boolean existsByWorkAndHolder(Work work, User holder);

  // maxParticipants cap: how many distinct people have drawn in this work.
  @Query("SELECT COUNT(DISTINCT t.holder) FROM Ticket t WHERE t.work = :work")
  long countDistinctHoldersByWork(@Param("work") Work work);

  // Lookahead / maxRounds bookkeeping for check().
  long countByWorkAndStatus(Work work, TicketStatus status);

  long countByWorkAndStatusIn(Work work, Collection<TicketStatus> statuses);

  // The single ACTIVE ticket (single-thread invariant: at most one per work).
  Optional<Ticket> findFirstByWorkAndStatus(Work work, TicketStatus status);

  // The queue head: lowest-numbered WAITING, i.e. the next ticket to activate.
  Optional<Ticket> findFirstByWorkAndStatusOrderByTicketNumberAsc(Work work, TicketStatus status);

  // Next ticket_number to assign on draw. Null means "no tickets yet" -> caller starts at 1.
  @Query("SELECT MAX(t.ticketNumber) FROM Ticket t WHERE t.work = :work")
  Long findMaxTicketNumber(@Param("work") Work work);

  // The whole queue for a work (all statuses, including CONSUMED/EXPIRED history) ordered by number
  // — backs the guest-readable ticket-list endpoint. JOIN FETCH holder + LEFT JOIN FETCH
  // contribution so the mapper can read them after the session closes (no N+1).
  @Query("SELECT t FROM Ticket t JOIN FETCH t.holder LEFT JOIN FETCH t.contribution "
      + "WHERE t.work = :work ORDER BY t.ticketNumber ASC")
  List<Ticket> findByWorkWithHolderOrderByTicketNumberAsc(@Param("work") Work work);

  // Scheduler sweep (timeout backstop, first path): all ACTIVE tickets already past expiry.
  List<Ticket> findByStatusAndExpiresAtBefore(TicketStatus status, LocalDateTime cutoff);
}
