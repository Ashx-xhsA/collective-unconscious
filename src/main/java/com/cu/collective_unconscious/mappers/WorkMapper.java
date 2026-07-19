package com.cu.collective_unconscious.mappers;

import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.dtos.WorkDetailDto;
import com.cu.collective_unconscious.dtos.WorkListItemDto;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(
        componentModel = "spring",
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        uses = {TagMapper.class, UserMapper.class})
public interface WorkMapper {

  @Mapping(target = "requiresAccessCode", expression = "java(work.getAccessCode() != null)")
  WorkListItemDto toListItemDto(Work work);

  @Mapping(target = "requiresAccessCode", expression = "java(work.getAccessCode() != null)")
  WorkDetailDto toDetailDto(Work work);
}