package com.cu.collective_unconscious.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Restricted update: only {@code completed} and {@code accessCode} may change (2026-07-17 decision
 * to narrow §2.2's implied "owner can also edit visibility/access code"). Null on either field
 * means "leave unchanged"; an empty {@code accessCode} is rejected (an access code, once set,
 * cannot be cleared this round — see service-level validation).
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UpdateWorkRequestDto {
  private Boolean completed;
  private String accessCode;
}
