package com.cu.collective_unconscious.services.impl;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.dtos.CreateWorkRequestDto;
import com.cu.collective_unconscious.dtos.UpdateWorkRequestDto;
import com.cu.collective_unconscious.dtos.WorkDetailDto;
import com.cu.collective_unconscious.dtos.WorkListItemDto;
import com.cu.collective_unconscious.mappers.WorkMapper;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.services.TagService;
import com.cu.collective_unconscious.services.WorkService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class WorkServiceImpl implements WorkService {

  private final WorkRepository workRepository;
  private final TagService tagService;
  private final WorkMapper workMapper;

  // readOnly = true keeps the session open through the mapping below, so
  // workMapper can read work.getTags() without LazyInitializationException.
  @Transactional(readOnly = true)
  @Override
  public List<WorkListItemDto> getWorkListItemDtos(UUID tagId) {
    List<Work> works;
    if (tagId != null) {
      Tag tag = tagService.getTagById(tagId);
      works = workRepository.findByVisibilityAndTagWithTags(Visibility.PUBLIC, tag);
    } else {
      works = workRepository.findByVisibilityWithTags(Visibility.PUBLIC);
    }
    return works.stream().map(workMapper::toListItemDto).toList();
  }

  // Request -> entity is built here (not a MapStruct toEntity) because null optional fields must
  // fall back to the entity's own @Builder.Default values, not overwrite them with null.
  @Transactional
  @Override
  public WorkDetailDto createWork(CreateWorkRequestDto dto, User author) {
    Work.WorkBuilder builder = Work.builder()
        .title(dto.getTitle())
        .preface(dto.getPreface())
        .workType(dto.getWorkType())
        .turnStyle(dto.getTurnStyle())
        .writingMode(dto.getWritingMode())
        .accessCode(dto.getAccessCode())
        .maxRounds(dto.getMaxRounds())
        .maxParticipants(dto.getMaxParticipants())
        .createdAuthor(author);

    if (dto.getVisibility() != null) {
      builder.visibility(dto.getVisibility());
    }
    if (dto.getMaxWords() != null) {
      builder.maxWords(dto.getMaxWords());
    }
    if (dto.getMaxParagraphs() != null) {
      builder.maxParagraphs(dto.getMaxParagraphs());
    }
    if (dto.getTicketTtlSeconds() != null) {
      builder.ticketTtlSeconds(dto.getTicketTtlSeconds());
    }

    Set<Tag> tags = new HashSet<>();
    if (dto.getTagNames() != null && !dto.getTagNames().isEmpty()) {
      tags.addAll(tagService.createTags(dto.getTagNames()));
    }
    builder.tags(tags);

    Work saved = workRepository.save(builder.build());
    return workMapper.toDetailDto(saved);
  }

  @Transactional(readOnly = true)
  @Override
  public WorkDetailDto getWorkDetailDto(UUID id) {
    Work work = workRepository.findWithTagsById(id)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + id));
    return workMapper.toDetailDto(work);
  }

  // Restricted update (2026-07-17 decision): only completed/accessCode may change here.
  // TODO(auth): once write permissions land, only the 题主 (createdAuthor) may call this.
  @Transactional
  @Override
  public WorkDetailDto updateWork(UUID id, UpdateWorkRequestDto dto) {
    Work work = workRepository.findWithTagsById(id)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + id));

    if (dto.getCompleted() != null) {
      // A completed work is final — completing is one-way, no "un-completing" back to in-progress.
      if (work.isCompleted() && !dto.getCompleted()) {
        throw new IllegalStateException("A completed work cannot be marked incomplete again");
      }
      work.setCompleted(dto.getCompleted());
    }
    if (dto.getAccessCode() != null) {
      // An access code, once set, cannot be cleared this round — see §4.5/decision 3.
      if (dto.getAccessCode().isEmpty()) {
        throw new IllegalArgumentException("accessCode cannot be cleared once set");
      }
      work.setAccessCode(dto.getAccessCode());
    }

    Work saved = workRepository.save(work);
    return workMapper.toDetailDto(saved);
  }
}
