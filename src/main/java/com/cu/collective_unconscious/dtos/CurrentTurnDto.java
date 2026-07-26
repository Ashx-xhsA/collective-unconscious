package com.cu.collective_unconscious.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Login-only snapshot of "is it my turn right now" for a work (plan §3.4 endpoint 2, revised
 * 2026-07-23). All active-* fields are null when no one currently holds an ACTIVE ticket.
 * {@code itIsMyTurn} is true only when the caller holds that ACTIVE ticket. The full queue (and a
 * caller's waiting position) is read from the guest-readable ticket-list endpoint instead.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CurrentTurnDto {
  private UUID workId;
  private UUID activeHolderId;
  private String activeHolderUsername;
  private Long activeTicketNumber;
  private LocalDateTime expiresAt;
  private boolean itIsMyTurn;
}
