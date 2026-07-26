package com.cu.collective_unconscious.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Outward view of one writing-permission ticket. Deliberately user-independent (same bytes for
 * every caller, even guests): the front-end derives "is this mine" from {@code holderId} vs its own
 * id. {@code activatedAt}/{@code expiresAt} are null until the ticket becomes ACTIVE;
 * {@code contributionId} is null until it is CONSUMED.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TicketDto {
  private UUID id;
  private UUID workId;
  private long ticketNumber;
  private String status;
  private UUID holderId;
  private String holderUsername;
  private LocalDateTime activatedAt;
  private LocalDateTime expiresAt;
  private UUID contributionId;
}
