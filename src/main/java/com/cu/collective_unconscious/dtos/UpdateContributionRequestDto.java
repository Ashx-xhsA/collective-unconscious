package com.cu.collective_unconscious.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Draft edit request ("save draft" button, 2nd+ click). Both fields nullable = leave unchanged.
 * Only valid against a still-{@code draft=true} contribution — see service-level guard.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class UpdateContributionRequestDto {
  private String title;
  private String content;
}
