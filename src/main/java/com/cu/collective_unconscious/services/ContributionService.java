package com.cu.collective_unconscious.services;


import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.dtos.ContributionDraftDto;
import com.cu.collective_unconscious.dtos.ContributionDto;
import com.cu.collective_unconscious.dtos.CreateContributionRequestDto;
import com.cu.collective_unconscious.dtos.UpdateContributionRequestDto;

import java.util.List;
import java.util.UUID;

public interface ContributionService {
  List<ContributionDraftDto> getDraftContributionDtos(User user);

  ContributionDraftDto createDraft(UUID workId, CreateContributionRequestDto dto, User author);

  ContributionDraftDto updateDraft(UUID id, UpdateContributionRequestDto dto, User user);

  ContributionDraftDto submit(UUID id, User user);

  List<ContributionDto> getContributionDtos(UUID workId, Integer afterSequence, int limit);

  ContributionDto getContributionDto(UUID id);
}
