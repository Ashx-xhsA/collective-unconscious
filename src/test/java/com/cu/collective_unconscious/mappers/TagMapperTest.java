package com.cu.collective_unconscious.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.dtos.TagDto;

/**
 * Unit test for the MapStruct-generated {@link TagMapper}. No Spring context — the generated
 * implementation is exercised directly. Confirms every outward field is copied and the primitive
 * {@code long workCount} widens cleanly into the DTO's {@code Integer}.
 */
class TagMapperTest {

  private final TagMapper mapper = Mappers.getMapper(TagMapper.class);

  @Test
  void toDto_copiesIdNameAndWorkCount() {
    UUID id = UUID.randomUUID();
    Tag tag = Tag.builder().id(id).name("fantasy").workCount(5).build();

    TagDto dto = mapper.toDto(tag);

    assertThat(dto.getId()).isEqualTo(id);
    assertThat(dto.getName()).isEqualTo("fantasy");
    assertThat(dto.getWorkCount()).isEqualTo(5); // long -> Integer
  }

  @Test
  void toDto_mapsZeroWorkCount() {
    Tag tag = Tag.builder().id(UUID.randomUUID()).name("fresh").workCount(0).build();

    assertThat(mapper.toDto(tag).getWorkCount()).isZero();
  }

  @Test
  void toDto_returnsNull_forNullInput() {
    assertThat(mapper.toDto(null)).isNull();
  }
}
