package com.cu.collective_unconscious.dtos;

import jakarta.validation.constraints.NotBlank;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Draft creation request. {@code title} is only meaningful for {@code Novel} works (chapter
 * title); {@code Article} works leave it null. Content limits (max words/paragraphs) come from
 * the parent {@code Work}, not static annotations here — see the service-level §4.5 validation.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class CreateContributionRequestDto {
  private String title;

  @NotBlank(message = "Content is required")
  private String content;
}
