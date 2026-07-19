package com.cu.collective_unconscious.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;
import com.cu.collective_unconscious.dtos.CreateWorkRequestDto;
import com.cu.collective_unconscious.dtos.UpdateWorkRequestDto;
import com.cu.collective_unconscious.dtos.WorkDetailDto;
import com.cu.collective_unconscious.dtos.WorkListItemDto;
import com.cu.collective_unconscious.mappers.WorkMapper;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.services.impl.WorkServiceImpl;

import jakarta.persistence.EntityNotFoundException;

/**
 * Pure unit test for {@link WorkServiceImpl}. Repository, tag service and mapper are all mocked,
 * so this pins the branching contract of the listing query: no tag id -> "all public works", a
 * tag id -> "public works carrying that tag", and an unknown tag id -> propagate 404. The service
 * must only ever surface PUBLIC works, never UNLISTED ones.
 */
@ExtendWith(MockitoExtension.class)
class WorkServiceImplTest {

  @Mock WorkRepository workRepository;
  @Mock TagService tagService;
  @Mock WorkMapper workMapper;
  @InjectMocks WorkServiceImpl service;

  private Work work() {
    return Work.builder().id(UUID.randomUUID()).title("t").build();
  }

  @Test
  void getWorkListItemDtos_withoutTag_listsAllPublicWorks() {
    Work w = work();
    WorkListItemDto dto = WorkListItemDto.builder().id(w.getId()).build();
    when(workRepository.findByVisibilityWithTags(Visibility.PUBLIC)).thenReturn(List.of(w));
    when(workMapper.toListItemDto(w)).thenReturn(dto);

    List<WorkListItemDto> result = service.getWorkListItemDtos(null);

    assertThat(result).containsExactly(dto);
    // No tag id -> the tag service is never consulted, and the tag-filtered query is never used.
    verifyNoInteractions(tagService);
    verify(workRepository, never()).findByVisibilityAndTagWithTags(Visibility.PUBLIC, null);
  }

  @Test
  void getWorkListItemDtos_withTag_listsPublicWorksCarryingThatTag() {
    UUID tagId = UUID.randomUUID();
    Tag tag = Tag.builder().id(tagId).name("fantasy").build();
    Work w = work();
    WorkListItemDto dto = WorkListItemDto.builder().id(w.getId()).build();
    when(tagService.getTagById(tagId)).thenReturn(tag);
    when(workRepository.findByVisibilityAndTagWithTags(Visibility.PUBLIC, tag))
        .thenReturn(List.of(w));
    when(workMapper.toListItemDto(w)).thenReturn(dto);

    List<WorkListItemDto> result = service.getWorkListItemDtos(tagId);

    assertThat(result).containsExactly(dto);
    verify(workRepository).findByVisibilityAndTagWithTags(Visibility.PUBLIC, tag);
    verify(workRepository, never()).findByVisibilityWithTags(Visibility.PUBLIC);
  }

  @Test
  void getWorkListItemDtos_withUnknownTag_propagatesNotFound() {
    UUID tagId = UUID.randomUUID();
    when(tagService.getTagById(tagId))
        .thenThrow(new EntityNotFoundException("Tag not found with id" + tagId));

    assertThatThrownBy(() -> service.getWorkListItemDtos(tagId))
        .isInstanceOf(EntityNotFoundException.class);
    // A bad filter must not fall back to listing everything.
    verify(workRepository, never()).findByVisibilityWithTags(Visibility.PUBLIC);
  }

  @Test
  void getWorkListItemDtos_returnsEmpty_whenNoPublicWorks() {
    when(workRepository.findByVisibilityWithTags(Visibility.PUBLIC)).thenReturn(List.of());

    assertThat(service.getWorkListItemDtos(null)).isEmpty();
  }

  // --- createWork ------------------------------------------------------------

  private User author() {
    return User.builder().id(UUID.randomUUID()).username("author").build();
  }

  private CreateWorkRequestDto.CreateWorkRequestDtoBuilder requestDtoBuilder() {
    return CreateWorkRequestDto.builder()
        .title("t")
        .preface("p")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT);
  }

  @Test
  void createWork_happyPath_buildsEntityFromDto_setsCreatedAuthor_andReturnsMappedDetailDto() {
    User author = author();
    Tag tag = Tag.builder().id(UUID.randomUUID()).name("fantasy").build();
    CreateWorkRequestDto dto = requestDtoBuilder()
        .visibility(Visibility.UNLISTED)
        .accessCode("secret")
        .maxRounds(10)
        .maxWords(500)
        .maxParagraphs(3)
        .ticketTtlSeconds(60L)
        .maxParticipants(5)
        .tagNames(Set.of("fantasy"))
        .build();
    WorkDetailDto expected = WorkDetailDto.builder().title("t").build();
    when(tagService.createTags(Set.of("fantasy"))).thenReturn(List.of(tag));
    when(workRepository.save(any(Work.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workMapper.toDetailDto(any(Work.class))).thenReturn(expected);

    WorkDetailDto result = service.createWork(dto, author);

    assertThat(result).isSameAs(expected);
    ArgumentCaptor<Work> captor = ArgumentCaptor.forClass(Work.class);
    verify(workRepository).save(captor.capture());
    Work saved = captor.getValue();
    assertThat(saved.getTitle()).isEqualTo("t");
    assertThat(saved.getPreface()).isEqualTo("p");
    assertThat(saved.getCreatedAuthor()).isEqualTo(author);
    assertThat(saved.getWorkType()).isEqualTo(WorkType.NOVEL);
    assertThat(saved.getTurnStyle()).isEqualTo(TurnStyle.ROBINROUND);
    assertThat(saved.getWritingMode()).isEqualTo(WritingMode.DEFAULT);
    assertThat(saved.getVisibility()).isEqualTo(Visibility.UNLISTED);
    assertThat(saved.getAccessCode()).isEqualTo("secret");
    assertThat(saved.getMaxRounds()).isEqualTo(10);
    assertThat(saved.getMaxWords()).isEqualTo(500);
    assertThat(saved.getMaxParagraphs()).isEqualTo(3);
    assertThat(saved.getTicketTtlSeconds()).isEqualTo(60L);
    assertThat(saved.getMaxParticipants()).isEqualTo(5);
    assertThat(saved.getTags()).containsExactly(tag);
  }

  @Test
  void createWork_withNullOptionalFields_usesEntityDefaults_andSkipsTagService() {
    User author = author();
    CreateWorkRequestDto dto = requestDtoBuilder().build(); // visibility/limits/tagNames all null
    when(workRepository.save(any(Work.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workMapper.toDetailDto(any(Work.class))).thenReturn(WorkDetailDto.builder().build());

    service.createWork(dto, author);

    ArgumentCaptor<Work> captor = ArgumentCaptor.forClass(Work.class);
    verify(workRepository).save(captor.capture());
    Work saved = captor.getValue();
    assertThat(saved.getVisibility()).isEqualTo(Visibility.PUBLIC);
    assertThat(saved.getMaxWords()).isEqualTo(1000);
    assertThat(saved.getMaxParagraphs()).isEqualTo(1);
    assertThat(saved.getTicketTtlSeconds()).isEqualTo(3600L);
    assertThat(saved.getMaxRounds()).isNull();
    assertThat(saved.getMaxParticipants()).isNull();
    assertThat(saved.getAccessCode()).isNull();
    assertThat(saved.getTags()).isEmpty();
    verifyNoInteractions(tagService);
  }

  @Test
  void createWork_withEmptyTagNames_skipsTagService_andSavesWithEmptyTags() {
    User author = author();
    CreateWorkRequestDto dto = requestDtoBuilder().tagNames(Set.of()).build();
    when(workRepository.save(any(Work.class))).thenAnswer(inv -> inv.getArgument(0));
    when(workMapper.toDetailDto(any(Work.class))).thenReturn(WorkDetailDto.builder().build());

    service.createWork(dto, author);

    verifyNoInteractions(tagService);
    ArgumentCaptor<Work> captor = ArgumentCaptor.forClass(Work.class);
    verify(workRepository).save(captor.capture());
    assertThat(captor.getValue().getTags()).isEmpty();
  }

  // --- getWorkDetailDto --------------------------------------------------

  @Test
  void getWorkDetailDto_found_returnsMappedDetailDto() {
    Work w = work();
    WorkDetailDto expected = WorkDetailDto.builder().id(w.getId()).build();
    when(workRepository.findWithTagsById(w.getId())).thenReturn(Optional.of(w));
    when(workMapper.toDetailDto(w)).thenReturn(expected);

    WorkDetailDto result = service.getWorkDetailDto(w.getId());

    assertThat(result).isSameAs(expected);
  }

  @Test
  void getWorkDetailDto_notFound_throwsEntityNotFound() {
    UUID id = UUID.randomUUID();
    when(workRepository.findWithTagsById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getWorkDetailDto(id))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // --- updateWork ----------------------------------------------------------

  private Work workWithDefaults() {
    return Work.builder().id(UUID.randomUUID()).completed(false).accessCode(null).build();
  }

  @Test
  void updateWork_setsCompleted_whenNonNull() {
    Work w = workWithDefaults();
    when(workRepository.findWithTagsById(w.getId())).thenReturn(Optional.of(w));
    when(workRepository.save(w)).thenReturn(w);
    when(workMapper.toDetailDto(w)).thenReturn(WorkDetailDto.builder().build());

    service.updateWork(w.getId(), UpdateWorkRequestDto.builder().completed(true).build());

    assertThat(w.isCompleted()).isTrue();
  }

  @Test
  void updateWork_setsAccessCode_whenNonEmpty() {
    Work w = workWithDefaults();
    when(workRepository.findWithTagsById(w.getId())).thenReturn(Optional.of(w));
    when(workRepository.save(w)).thenReturn(w);
    when(workMapper.toDetailDto(w)).thenReturn(WorkDetailDto.builder().build());

    service.updateWork(w.getId(), UpdateWorkRequestDto.builder().accessCode("secret").build());

    assertThat(w.getAccessCode()).isEqualTo("secret");
  }

  @Test
  void updateWork_emptyAccessCode_throwsIllegalArgument_andDoesNotSave() {
    Work w = workWithDefaults();
    w.setAccessCode("already-set");
    when(workRepository.findWithTagsById(w.getId())).thenReturn(Optional.of(w));

    assertThatThrownBy(() ->
        service.updateWork(w.getId(), UpdateWorkRequestDto.builder().accessCode("").build()))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(w.getAccessCode()).isEqualTo("already-set"); // unchanged
    verify(workRepository, never()).save(any(Work.class));
  }

  @Test
  void updateWork_bothFieldsNull_leavesWorkUnchanged() {
    Work w = workWithDefaults();
    w.setAccessCode("kept");
    when(workRepository.findWithTagsById(w.getId())).thenReturn(Optional.of(w));
    when(workRepository.save(w)).thenReturn(w);
    when(workMapper.toDetailDto(w)).thenReturn(WorkDetailDto.builder().build());

    service.updateWork(w.getId(), UpdateWorkRequestDto.builder().build());

    assertThat(w.isCompleted()).isFalse();
    assertThat(w.getAccessCode()).isEqualTo("kept");
  }

  @Test
  void updateWork_unknownId_throwsEntityNotFound() {
    UUID id = UUID.randomUUID();
    when(workRepository.findWithTagsById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() ->
        service.updateWork(id, UpdateWorkRequestDto.builder().completed(true).build()))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void updateWork_alreadyCompleted_cannotBeSetToFalse_throwsIllegalState_andDoesNotSave() {
    Work w = Work.builder().id(UUID.randomUUID()).completed(true).build();
    when(workRepository.findWithTagsById(w.getId())).thenReturn(Optional.of(w));

    assertThatThrownBy(() ->
        service.updateWork(w.getId(), UpdateWorkRequestDto.builder().completed(false).build()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(w.isCompleted()).isTrue(); // unchanged
    verify(workRepository, never()).save(any(Work.class));
  }

  @Test
  void updateWork_alreadyCompleted_settingCompletedTrueAgain_isANoOp_doesNotThrow() {
    Work w = Work.builder().id(UUID.randomUUID()).completed(true).build();
    when(workRepository.findWithTagsById(w.getId())).thenReturn(Optional.of(w));
    when(workRepository.save(w)).thenReturn(w);
    when(workMapper.toDetailDto(w)).thenReturn(WorkDetailDto.builder().build());

    service.updateWork(w.getId(), UpdateWorkRequestDto.builder().completed(true).build());

    assertThat(w.isCompleted()).isTrue();
  }
}
