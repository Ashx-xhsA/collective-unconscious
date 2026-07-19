package com.cu.collective_unconscious.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * "Read view" of one submitted (draft=false) contribution — full body, for the per-work cursor
 * list and the single-chapter direct read (§3.4). Novel and Article both carry {@code content};
 * front-end tells them apart via the parent work's {@code workType}.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContributionDto {
  private UUID id;
  private UUID workId;
  private Integer sequenceNumber;
  private String title;
  private String content;
  private AuthorDto author;
  private LocalDateTime createdAt;
}
