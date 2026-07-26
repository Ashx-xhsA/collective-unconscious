package com.cu.collective_unconscious.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Draw-ticket request body. {@code accessCode} is required only when the work carries one (its
 * invitation code, §2.2); otherwise it may be null. Validated in the service, not here, because
 * whether it is required depends on the target work.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class DrawTicketRequestDto {
  private String accessCode;
}
