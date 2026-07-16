package com.cu.collective_unconscious.mappers;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.dtos.TagResponse;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.ReportingPolicy;

import java.util.Set;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TagMapper  {
  TagResponse toTagResponse(Tag tag);

}
