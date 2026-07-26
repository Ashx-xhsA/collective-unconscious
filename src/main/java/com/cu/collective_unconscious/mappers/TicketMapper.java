package com.cu.collective_unconscious.mappers;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.dtos.TicketDto;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface TicketMapper {

  // Called inside the service transaction so the LAZY work/holder/contribution can be read.
  // status (enum) -> String is handled by MapStruct via name(); nested contribution.id is null-safe.
  @Mapping(target = "workId", source = "work.id")
  @Mapping(target = "holderId", source = "holder.id")
  @Mapping(target = "holderUsername", source = "holder.username")
  @Mapping(target = "contributionId", source = "contribution.id")
  TicketDto toDto(Ticket ticket);
}
