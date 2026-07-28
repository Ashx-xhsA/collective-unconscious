package com.cu.collective_unconscious.services;

import java.util.List;
import java.util.UUID;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.dtos.CurrentTurnDto;
import com.cu.collective_unconscious.dtos.TicketDto;

public interface TicketService {

  // Draw a ticket for `user` on the given work (WAITING, or immediately ACTIVE if the queue was
  // idle). `accessCode` is required only when the work carries one.
  TicketDto drawTicket(UUID workId, User user, String accessCode);

  // The whole ticket queue (all statuses, ordered by number) for a work. Guest-readable, pure read.
  List<TicketDto> listTickets(UUID workId);

  // Login-only "is it my turn" snapshot. Pure read.
  CurrentTurnDto getCurrentTurn(UUID workId, User user);

  // Submit seam (plan step 5): fencing-check the caller's ACTIVE ticket, consume it, re-enqueue
  // (RobinRound) and advance — all inside the caller's submit transaction.
  void consumeActiveTicketForSubmit(Work work, User user, Contribution contribution);

  // Scheduler entry (plan step 6): expire the timed-out ACTIVE ticket (if any) and activate the
  // next one, in its own transaction.
  void advanceWork(UUID workId);
}
