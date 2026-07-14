package com.cu.collective_unconscious.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.dtos.UserDto;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface UserMapper {

  // Role (enum) -> String is handled by MapStruct automatically via name().
  UserDto toDto(User user);
}
