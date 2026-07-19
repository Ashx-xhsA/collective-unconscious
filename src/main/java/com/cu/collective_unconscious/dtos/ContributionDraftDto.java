package com.cu.collective_unconscious.dtos;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ContributionDraftDto {
  private UUID id;
  private UUID workId;
  private String workTitle;
  private String title;
  private String content;
  private Integer sequenceNumber;
  private LocalDateTime createdAt;
}
