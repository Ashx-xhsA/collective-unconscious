package com.cu.collective_unconscious.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.test.util.ReflectionTestUtils;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.dtos.ContributionDraftDto;
import com.cu.collective_unconscious.dtos.ContributionDto;

/**
 * Unit test for the MapStruct-generated {@link ContributionMapper}. The interesting part is the
 * nested mapping: {@code work.id -> workId} and {@code work.title -> workTitle} are flattened onto
 * the draft DTO so the client can label a draft without a second fetch. {@code toDto} additionally
 * delegates {@code author -> AuthorDto} to {@link UserMapper}; unlike a collection-valued field,
 * MapStruct calls that collaborator unconditionally (no null guard), so
 * {@link Mappers#getMapper} alone leaves it null and NPEs — we wire a real {@code UserMapperImpl}
 * in by hand since this mapper's spring componentModel means there's no Spring context here.
 */
class ContributionMapperTest {

  private final ContributionMapper mapper = Mappers.getMapper(ContributionMapper.class);

  @BeforeEach
  void wireCollaborators() {
    ReflectionTestUtils.setField(mapper, "userMapper", Mappers.getMapper(UserMapper.class));
  }

  @Test
  void toDraftDto_flattensWorkIdAndTitle_andCopiesScalarFields() {
    UUID workId = UUID.randomUUID();
    UUID contributionId = UUID.randomUUID();
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 18, 10, 0);
    Work work = Work.builder().id(workId).title("The Long Story").build();
    Contribution contribution = Contribution.builder()
        .id(contributionId)
        .work(work)
        .title("Chapter 1")
        .content("Once upon a time")
        .sequenceNumber(1)
        .createdAt(createdAt)
        .build();

    ContributionDraftDto dto = mapper.toDraftDto(contribution);

    assertThat(dto.getId()).isEqualTo(contributionId);
    assertThat(dto.getWorkId()).isEqualTo(workId);
    assertThat(dto.getWorkTitle()).isEqualTo("The Long Story");
    assertThat(dto.getTitle()).isEqualTo("Chapter 1");
    assertThat(dto.getContent()).isEqualTo("Once upon a time");
    assertThat(dto.getSequenceNumber()).isEqualTo(1);
    assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
  }

  @Test
  void toDraftDto_toleratesNullTitleAndSequence() {
    // An Article draft carries no chapter title, and a draft has no sequence until submit (§2.4).
    Work work = Work.builder().id(UUID.randomUUID()).title("Untitled").build();
    Contribution contribution = Contribution.builder()
        .id(UUID.randomUUID())
        .work(work)
        .content("body only")
        .build();

    ContributionDraftDto dto = mapper.toDraftDto(contribution);

    assertThat(dto.getTitle()).isNull();
    assertThat(dto.getSequenceNumber()).isNull();
    assertThat(dto.getWorkTitle()).isEqualTo("Untitled");
  }

  @Test
  void toDraftDto_returnsNull_forNullInput() {
    assertThat(mapper.toDraftDto(null)).isNull();
  }

  // --- toDto (read view) ------------------------------------------------------

  @Test
  void toDto_flattensWorkId_andMapsAuthorToAuthorDto_withoutEmail() {
    UUID workId = UUID.randomUUID();
    UUID contributionId = UUID.randomUUID();
    UUID authorId = UUID.randomUUID();
    LocalDateTime createdAt = LocalDateTime.of(2026, 7, 18, 10, 0);
    Work work = Work.builder().id(workId).title("The Long Story").build();
    User author = User.builder()
        .id(authorId)
        .username("ash")
        .displayName("Ash")
        .email("ash@example.com")
        .build();
    Contribution contribution = Contribution.builder()
        .id(contributionId)
        .work(work)
        .author(author)
        .title("Chapter 1")
        .content("Once upon a time")
        .sequenceNumber(1)
        .draft(false)
        .createdAt(createdAt)
        .build();

    ContributionDto dto = mapper.toDto(contribution);

    assertThat(dto.getId()).isEqualTo(contributionId);
    assertThat(dto.getWorkId()).isEqualTo(workId);
    assertThat(dto.getSequenceNumber()).isEqualTo(1);
    assertThat(dto.getTitle()).isEqualTo("Chapter 1");
    assertThat(dto.getContent()).isEqualTo("Once upon a time");
    assertThat(dto.getCreatedAt()).isEqualTo(createdAt);
    assertThat(dto.getAuthor().getId()).isEqualTo(authorId);
    assertThat(dto.getAuthor().getUsername()).isEqualTo("ash");
    assertThat(dto.getAuthor().getDisplayName()).isEqualTo("Ash");
  }

  @Test
  void toDto_returnsNull_forNullInput() {
    assertThat(mapper.toDto(null)).isNull();
  }
}
