package com.cu.collective_unconscious.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;
import com.cu.collective_unconscious.dtos.WorkListItemDto;

/**
 * Unit test for {@link WorkMapper}. The mapper's own logic — the {@code requiresAccessCode}
 * expression and the straight field/enum copy — is exercised here with an empty tag set, so the
 * (Spring-injected) TagMapper collaborator is never dereferenced. Tag flattening is covered
 * end-to-end against the real wired mapper in the WorkController integration test.
 */
class WorkMapperTest {

  private final WorkMapper mapper = Mappers.getMapper(WorkMapper.class);

  private Work.WorkBuilder baseWork() {
    return Work.builder()
        .id(UUID.randomUUID())
        .title("A Title")
        .preface("A preface")
        .tags(new HashSet<>())
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .completed(false)
        .createdAt(LocalDateTime.of(2026, 7, 18, 9, 0))
        .updatedAt(LocalDateTime.of(2026, 7, 18, 9, 30));
  }

  @Test
  void toListItemDto_copiesScalarAndEnumFields() {
    Work work = baseWork().build();

    WorkListItemDto dto = mapper.toListItemDto(work);

    assertThat(dto.getId()).isEqualTo(work.getId());
    assertThat(dto.getTitle()).isEqualTo("A Title");
    assertThat(dto.getPreface()).isEqualTo("A preface");
    assertThat(dto.getWorkType()).isEqualTo(WorkType.NOVEL);
    assertThat(dto.getTurnStyle()).isEqualTo(TurnStyle.ROBINROUND);
    assertThat(dto.getWritingMode()).isEqualTo(WritingMode.DEFAULT);
    assertThat(dto.getVisibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(dto.isCompleted()).isFalse();
    assertThat(dto.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 9, 0));
    assertThat(dto.getUpdatedAt()).isEqualTo(LocalDateTime.of(2026, 7, 18, 9, 30));
    assertThat(dto.getTags()).isEmpty();
  }

  @Test
  void toListItemDto_requiresAccessCodeIsTrue_whenAccessCodePresent() {
    Work work = baseWork().accessCode("secret").build();

    // The DTO must expose only the boolean fact, never the code itself.
    assertThat(mapper.toListItemDto(work).isRequiresAccessCode()).isTrue();
  }

  @Test
  void toListItemDto_requiresAccessCodeIsFalse_whenAccessCodeNull() {
    Work work = baseWork().accessCode(null).build();

    assertThat(mapper.toListItemDto(work).isRequiresAccessCode()).isFalse();
  }

  @Test
  void toListItemDto_returnsNull_forNullInput() {
    assertThat(mapper.toListItemDto(null)).isNull();
  }
}
