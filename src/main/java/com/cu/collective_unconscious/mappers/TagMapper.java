package com.cu.collective_unconscious.mappers;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.dtos.TagDto;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TagMapper  {
  TagDto toDto(Tag tag);

}
