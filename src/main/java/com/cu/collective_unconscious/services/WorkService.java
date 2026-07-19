package com.cu.collective_unconscious.services;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.dtos.CreateWorkRequestDto;
import com.cu.collective_unconscious.dtos.UpdateWorkRequestDto;
import com.cu.collective_unconscious.dtos.WorkDetailDto;
import com.cu.collective_unconscious.dtos.WorkListItemDto;

import java.util.List;
import java.util.UUID;

public interface WorkService {
  List<WorkListItemDto> getWorkListItemDtos(UUID tagId);

  WorkDetailDto createWork(CreateWorkRequestDto dto, User author);

  WorkDetailDto getWorkDetailDto(UUID id);

  WorkDetailDto updateWork(UUID id, UpdateWorkRequestDto dto);
}
