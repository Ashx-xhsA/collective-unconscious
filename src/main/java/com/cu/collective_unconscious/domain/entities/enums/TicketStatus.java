package com.cu.collective_unconscious.domain.entities.enums;

/**
 * Lifecycle of a writing-permission ticket (see design doc §2.5).
 */
public enum TicketStatus {
  WAITING,
  ACTIVE,
  CONSUMED,
  EXPIRED,
  SKIPPED
}
