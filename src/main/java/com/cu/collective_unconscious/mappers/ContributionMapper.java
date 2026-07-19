package com.cu.collective_unconscious.mappers;


import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.dtos.ContributionDraftDto;
import com.cu.collective_unconscious.dtos.ContributionDto;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE, uses = UserMapper.class)
public interface ContributionMapper {

  @Mapping(target = "workId", source = "work.id")
  @Mapping(target = "workTitle", source = "work.title")
  ContributionDraftDto toDraftDto(Contribution contribution);

  // "author" (User) -> "author" (AuthorDto) is resolved via UserMapper.toAuthorDto (same field
  // name both sides, type-matched through `uses`) — never leaks email/role/password.
  @Mapping(target = "workId", source = "work.id")
  ContributionDto toDto(Contribution contribution);
}
