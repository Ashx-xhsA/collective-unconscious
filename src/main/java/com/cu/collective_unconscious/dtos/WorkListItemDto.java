package com.cu.collective_unconscious.dtos;

import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class WorkListItemDto {
  private UUID id;

  private String title;
  private String preface;
  private Set<TagDto> tags;

  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  private WorkType workType;
  private TurnStyle turnStyle;
  private WritingMode writingMode;

  private Visibility visibility;
  private boolean requiresAccessCode;
  private boolean completed;

}
