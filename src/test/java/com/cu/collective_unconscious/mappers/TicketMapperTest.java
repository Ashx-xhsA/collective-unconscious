package com.cu.collective_unconscious.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;
import com.cu.collective_unconscious.dtos.TicketDto;

/**
 * Unit test for the MapStruct-generated {@link TicketMapper}. It flattens work/holder/contribution
 * ids onto the DTO. The DTO is user-independent — "is this mine" is derived front-end side from
 * holderId, so the mapper takes no caller context.
 */
class TicketMapperTest {

  private final TicketMapper mapper = Mappers.getMapper(TicketMapper.class);

  private Ticket ticket(User holder, TicketStatus status) {
    return Ticket.builder()
        .id(UUID.randomUUID())
        .work(Work.builder().id(UUID.randomUUID()).build())
        .holder(holder)
        .ticketNumber(3)
        .status(status)
        .activatedAt(LocalDateTime.of(2026, 7, 21, 10, 0))
        .expiresAt(LocalDateTime.of(2026, 7, 21, 11, 0))
        .build();
  }

  @Test
  void toDto_flattensIdsAndScalars_andMapsStatusToName() {
    User holder = User.builder().id(UUID.randomUUID()).username("ash").build();
    Ticket ticket = ticket(holder, TicketStatus.ACTIVE);

    TicketDto dto = mapper.toDto(ticket);

    assertThat(dto.getId()).isEqualTo(ticket.getId());
    assertThat(dto.getWorkId()).isEqualTo(ticket.getWork().getId());
    assertThat(dto.getTicketNumber()).isEqualTo(3);
    assertThat(dto.getStatus()).isEqualTo("ACTIVE");
    assertThat(dto.getHolderId()).isEqualTo(holder.getId());
    assertThat(dto.getHolderUsername()).isEqualTo("ash");
    assertThat(dto.getActivatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 21, 10, 0));
    assertThat(dto.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 7, 21, 11, 0));
  }

  @Test
  void toDto_contributionId_nullWhenNotConsumed() {
    User holder = User.builder().id(UUID.randomUUID()).username("ash").build();
    TicketDto dto = mapper.toDto(ticket(holder, TicketStatus.WAITING));
    assertThat(dto.getContributionId()).isNull();
  }

  @Test
  void toDto_contributionId_setWhenConsumed() {
    User holder = User.builder().id(UUID.randomUUID()).username("ash").build();
    Ticket ticket = ticket(holder, TicketStatus.CONSUMED);
    UUID contributionId = UUID.randomUUID();
    ticket.setContribution(Contribution.builder().id(contributionId).build());

    TicketDto dto = mapper.toDto(ticket);

    assertThat(dto.getContributionId()).isEqualTo(contributionId);
  }

  @Test
  void toDto_returnsNull_forNullInput() {
    assertThat(mapper.toDto(null)).isNull();
  }
}
