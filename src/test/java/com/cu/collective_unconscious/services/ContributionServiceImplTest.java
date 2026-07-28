package com.cu.collective_unconscious.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.dtos.ContributionDraftDto;
import com.cu.collective_unconscious.dtos.ContributionDto;
import com.cu.collective_unconscious.dtos.CreateContributionRequestDto;
import com.cu.collective_unconscious.dtos.UpdateContributionRequestDto;
import com.cu.collective_unconscious.mappers.ContributionMapper;
import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.services.impl.ContributionServiceImpl;

import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import jakarta.persistence.EntityNotFoundException;

/**
 * Pure unit test for {@link ContributionServiceImpl}. Repository and mapper are mocked. The one
 * business rule to lock in: the service always queries with {@code draft = true}, so submitted
 * contributions can never leak into a user's draft list.
 */
@ExtendWith(MockitoExtension.class)
class ContributionServiceImplTest {

  @Mock ContributionRepository contributionRepository;
  @Mock ContributionMapper contributionMapper;
  @Mock WorkRepository workRepository;
  @Mock TicketService ticketService;
  @InjectMocks ContributionServiceImpl service;

  @Test
  void getDraftContributionDtos_mapsOnlyThisUsersDrafts() {
    User user = User.builder().id(UUID.randomUUID()).username("ash").build();
    Contribution draft = Contribution.builder().id(UUID.randomUUID()).content("wip").build();
    ContributionDraftDto dto = ContributionDraftDto.builder().id(draft.getId()).build();
    when(contributionRepository.findDraftsWithWork(user, true)).thenReturn(List.of(draft));
    when(contributionMapper.toDraftDto(draft)).thenReturn(dto);

    List<ContributionDraftDto> result = service.getDraftContributionDtos(user);

    assertThat(result).containsExactly(dto);
    // The draft flag must be hard-coded true — submitted contributions are never "drafts".
    verify(contributionRepository).findDraftsWithWork(user, true);
  }

  @Test
  void getDraftContributionDtos_returnsEmpty_whenUserHasNoDrafts() {
    User user = User.builder().id(UUID.randomUUID()).username("ash").build();
    when(contributionRepository.findDraftsWithWork(user, true)).thenReturn(List.of());

    assertThat(service.getDraftContributionDtos(user)).isEmpty();
  }

  // --- createDraft (+ §4.5 content validation) ------------------------------

  private User author() {
    return User.builder().id(UUID.randomUUID()).username("ash").build();
  }

  private Work work(WorkType type, int maxWords, int maxParagraphs, boolean completed) {
    return Work.builder()
        .id(UUID.randomUUID())
        .workType(type)
        .maxWords(maxWords)
        .maxParagraphs(maxParagraphs)
        .completed(completed)
        .build();
  }

  private void stubSaveEcho() {
    when(contributionRepository.save(any(Contribution.class))).thenAnswer(inv -> inv.getArgument(0));
    when(contributionMapper.toDraftDto(any(Contribution.class)))
        .thenReturn(ContributionDraftDto.builder().build());
  }

  @Test
  void createDraft_articleType_ignoresProvidedTitle_savesDraftWithNoSequenceNumber() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    stubSaveEcho();
    CreateContributionRequestDto dto = CreateContributionRequestDto.builder()
        .title("should be ignored")
        .content("a short paragraph")
        .build();

    service.createDraft(work.getId(), dto, author);

    ArgumentCaptor<Contribution> captor = ArgumentCaptor.forClass(Contribution.class);
    verify(contributionRepository).save(captor.capture());
    Contribution saved = captor.getValue();
    assertThat(saved.getTitle()).isNull();
    assertThat(saved.getContent()).isEqualTo("a short paragraph");
    assertThat(saved.isDraft()).isTrue();
    assertThat(saved.getSequenceNumber()).isNull();
    assertThat(saved.getWork()).isEqualTo(work);
    assertThat(saved.getAuthor()).isEqualTo(author);
  }

  @Test
  void createDraft_novelType_requiresAndKeepsTitle() {
    Work work = work(WorkType.NOVEL, 1000, 5, false);
    User author = author();
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    stubSaveEcho();
    CreateContributionRequestDto dto = CreateContributionRequestDto.builder()
        .title("Chapter One")
        .content("once upon a time")
        .build();

    service.createDraft(work.getId(), dto, author);

    ArgumentCaptor<Contribution> captor = ArgumentCaptor.forClass(Contribution.class);
    verify(contributionRepository).save(captor.capture());
    assertThat(captor.getValue().getTitle()).isEqualTo("Chapter One");
  }

  @Test
  void createDraft_novelType_blankTitle_throwsIllegalArgument_andDoesNotSave() {
    Work work = work(WorkType.NOVEL, 1000, 5, false);
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    CreateContributionRequestDto dto = CreateContributionRequestDto.builder()
        .title("  ")
        .content("once upon a time")
        .build();

    assertThatThrownBy(() -> service.createDraft(work.getId(), dto, author()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void createDraft_contentExceedsMaxWords_throwsIllegalArgument() {
    Work work = work(WorkType.ARTICLE, 5, 5, false);
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    CreateContributionRequestDto dto = CreateContributionRequestDto.builder()
        .content("this is way more than five characters")
        .build();

    assertThatThrownBy(() -> service.createDraft(work.getId(), dto, author()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void createDraft_charCount_usesGraphemeClusters_notRawUtf16Length() {
    // "cafe" + combining acute accent (U+0301) = 4 human-visible characters (café), 5 chars/
    // code-points. maxWords=4 must PASS here — a naive content.length()/codePointCount would
    // wrongly count 5 and reject it.
    Work work = work(WorkType.ARTICLE, 4, 5, false);
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    stubSaveEcho();
    CreateContributionRequestDto dto = CreateContributionRequestDto.builder()
        .content("café")
        .build();

    service.createDraft(work.getId(), dto, author());

    verify(contributionRepository).save(any(Contribution.class));
  }

  @Test
  void createDraft_contentExceedsMaxParagraphs_throwsIllegalArgument() {
    Work work = work(WorkType.ARTICLE, 1000, 1, false);
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    CreateContributionRequestDto dto = CreateContributionRequestDto.builder()
        .content("paragraph one\n\nparagraph two")
        .build();

    assertThatThrownBy(() -> service.createDraft(work.getId(), dto, author()))
        .isInstanceOf(IllegalArgumentException.class);
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void createDraft_workAlreadyCompleted_throwsIllegalState() {
    Work work = work(WorkType.ARTICLE, 1000, 5, true);
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    CreateContributionRequestDto dto = CreateContributionRequestDto.builder()
        .content("too late")
        .build();

    assertThatThrownBy(() -> service.createDraft(work.getId(), dto, author()))
        .isInstanceOf(IllegalStateException.class);
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void createDraft_unknownWorkId_throwsEntityNotFound() {
    UUID workId = UUID.randomUUID();
    when(workRepository.findById(workId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.createDraft(workId,
        CreateContributionRequestDto.builder().content("x").build(), author()))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // --- updateDraft -----------------------------------------------------------

  private Contribution draftContribution(String title, String content) {
    return Contribution.builder()
        .id(UUID.randomUUID())
        .title(title)
        .content(content)
        .draft(true)
        .build();
  }

  @Test
  void updateDraft_bothFieldsProvided_updatesTitleAndContent() {
    Contribution draft = draftContribution("old title", "old content");
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(contributionRepository.save(draft)).thenReturn(draft);
    when(contributionMapper.toDraftDto(draft)).thenReturn(ContributionDraftDto.builder().build());

    service.updateDraft(draft.getId(),
        UpdateContributionRequestDto.builder().title("new title").content("new content").build(),
        author());

    assertThat(draft.getTitle()).isEqualTo("new title");
    assertThat(draft.getContent()).isEqualTo("new content");
  }

  @Test
  void updateDraft_nullFields_leavesTitleAndContentUnchanged() {
    Contribution draft = draftContribution("old title", "old content");
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(contributionRepository.save(draft)).thenReturn(draft);
    when(contributionMapper.toDraftDto(draft)).thenReturn(ContributionDraftDto.builder().build());

    service.updateDraft(draft.getId(), UpdateContributionRequestDto.builder().build(), author());

    assertThat(draft.getTitle()).isEqualTo("old title");
    assertThat(draft.getContent()).isEqualTo("old content");
  }

  @Test
  void updateDraft_onlyContentProvided_updatesContentOnly() {
    Contribution draft = draftContribution("old title", "old content");
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(contributionRepository.save(draft)).thenReturn(draft);
    when(contributionMapper.toDraftDto(draft)).thenReturn(ContributionDraftDto.builder().build());

    service.updateDraft(draft.getId(),
        UpdateContributionRequestDto.builder().content("new content").build(), author());

    assertThat(draft.getTitle()).isEqualTo("old title");
    assertThat(draft.getContent()).isEqualTo("new content");
  }

  @Test
  void updateDraft_alreadySubmitted_throwsIllegalState_andDoesNotSave() {
    Contribution submitted = draftContribution("t", "c");
    submitted.setDraft(false);
    when(contributionRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

    assertThatThrownBy(() -> service.updateDraft(submitted.getId(),
        UpdateContributionRequestDto.builder().content("hacked").build(), author()))
        .isInstanceOf(IllegalStateException.class);

    assertThat(submitted.getContent()).isEqualTo("c"); // unchanged
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void updateDraft_unknownId_throwsEntityNotFound() {
    UUID id = UUID.randomUUID();
    when(contributionRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.updateDraft(id,
        UpdateContributionRequestDto.builder().content("x").build(), author()))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // --- submit ------------------------------------------------------------------

  private Contribution draftOn(Work work, User author) {
    return Contribution.builder()
        .id(UUID.randomUUID())
        .work(work)
        .author(author)
        .title(work.getWorkType() == WorkType.NOVEL ? "Chapter One" : null)
        .content("some content")
        .draft(true)
        .build();
  }

  private void stubContributionSaveEcho() {
    when(contributionRepository.save(any(Contribution.class))).thenAnswer(inv -> inv.getArgument(0));
    when(contributionMapper.toDraftDto(any(Contribution.class)))
        .thenReturn(ContributionDraftDto.builder().build());
  }

  @Test
  void submit_noPriorSubmissions_assignsSequenceNumberOne() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    Contribution draft = draftOn(work, author);
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    when(contributionRepository.findMaxSequenceNumber(work)).thenReturn(null);
    stubContributionSaveEcho();

    service.submit(draft.getId(), author);

    assertThat(draft.getSequenceNumber()).isEqualTo(1);
  }

  @Test
  void submit_priorSubmissionsExist_assignsNextSequenceNumber() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    Contribution draft = draftOn(work, author);
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    when(contributionRepository.findMaxSequenceNumber(work)).thenReturn(4);
    stubContributionSaveEcho();

    service.submit(draft.getId(), author);

    assertThat(draft.getSequenceNumber()).isEqualTo(5);
  }

  @Test
  void submit_setsDraftFalse_andAddsAuthorToWorkAuthors() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    Contribution draft = draftOn(work, author);
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    when(contributionRepository.findMaxSequenceNumber(work)).thenReturn(null);
    stubContributionSaveEcho();

    service.submit(draft.getId(), author);

    assertThat(draft.isDraft()).isFalse();
    assertThat(work.getAuthors()).contains(author);
  }

  @Test
  void submit_alreadySubmitted_throwsIllegalState_andDoesNotReassignSequence() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    Contribution submitted = draftOn(work, author);
    submitted.setDraft(false);
    submitted.setSequenceNumber(3);
    when(contributionRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

    assertThatThrownBy(() -> service.submit(submitted.getId(), author))
        .isInstanceOf(IllegalStateException.class);

    assertThat(submitted.getSequenceNumber()).isEqualTo(3);
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void submit_workAlreadyCompleted_throwsIllegalState_andDoesNotSave() {
    Work work = work(WorkType.ARTICLE, 1000, 5, true); // completed after the draft was written
    User author = author();
    Contribution draft = draftOn(work, author);
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));

    assertThatThrownBy(() -> service.submit(draft.getId(), author))
        .isInstanceOf(IllegalStateException.class);

    assertThat(draft.isDraft()).isTrue();
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void submit_contentNoLongerFitsLimits_throwsIllegalArgument_andDoesNotAssignSequence() {
    Work work = work(WorkType.ARTICLE, 3, 5, false); // limit tightened after the draft was written
    User author = author();
    Contribution draft = draftOn(work, author); // content is "some content" (> 3 chars)
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));

    assertThatThrownBy(() -> service.submit(draft.getId(), author))
        .isInstanceOf(IllegalArgumentException.class);

    assertThat(draft.getSequenceNumber()).isNull();
    verify(contributionRepository, never()).save(any());
  }

  @Test
  void submit_unknownId_throwsEntityNotFound() {
    UUID id = UUID.randomUUID();
    when(contributionRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.submit(id, author()))
        .isInstanceOf(EntityNotFoundException.class);
  }

  // --- submit: ticket seam + ownership (step 5) --------------------------------

  @Test
  void submit_notTheDraftAuthor_throwsAccessDenied_andConsumesNothing() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    User someoneElse = author(); // different random id
    Contribution draft = draftOn(work, author);
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));

    assertThatThrownBy(() -> service.submit(draft.getId(), someoneElse))
        .isInstanceOf(AccessDeniedException.class);

    verify(ticketService, never()).consumeActiveTicketForSubmit(any(), any(), any());
    verify(contributionRepository, never()).save(any());
    assertThat(draft.isDraft()).isTrue(); // untouched
  }

  @Test
  void submit_invokesTicketConsumeSeam_withWorkAuthorAndContribution() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    Contribution draft = draftOn(work, author);
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    when(contributionRepository.findMaxSequenceNumber(work)).thenReturn(null);
    stubContributionSaveEcho();

    service.submit(draft.getId(), author);

    // The seam is the fencing/consume/re-enqueue/advance step, run inside submit's transaction.
    verify(ticketService).consumeActiveTicketForSubmit(work, author, draft);
  }

  @Test
  void submit_ticketSeamRejects_propagates_andDoesNotAssignSequenceOrSave() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    User author = author();
    Contribution draft = draftOn(work, author);
    when(contributionRepository.findById(draft.getId())).thenReturn(Optional.of(draft));
    when(workRepository.findByIdForUpdate(work.getId())).thenReturn(Optional.of(work));
    // e.g. "it's not your turn / your ticket expired" -> 409 from the ticket service.
    doThrow(new IllegalStateException("it is not your turn"))
        .when(ticketService).consumeActiveTicketForSubmit(work, author, draft);

    assertThatThrownBy(() -> service.submit(draft.getId(), author))
        .isInstanceOf(IllegalStateException.class);

    assertThat(draft.getSequenceNumber()).isNull();
    assertThat(draft.isDraft()).isTrue();
    verify(contributionRepository, never()).save(any());
  }

  // --- getContributionDtos (cursor list) + getContributionDto (single) ---------

  @Test
  void getContributionDtos_delegatesToRepositoryWithWork_andMapsResults() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    Contribution c = Contribution.builder().id(UUID.randomUUID()).build();
    ContributionDto dto = ContributionDto.builder().id(c.getId()).build();
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    when(contributionRepository.findSubmittedAfterSequence(eq(work), eq(5), any(Pageable.class)))
        .thenReturn(List.of(c));
    when(contributionMapper.toDto(c)).thenReturn(dto);

    List<ContributionDto> result = service.getContributionDtos(work.getId(), 5, 20);

    assertThat(result).containsExactly(dto);
  }

  @Test
  void getContributionDtos_pageableUsesGivenLimit() {
    Work work = work(WorkType.ARTICLE, 1000, 5, false);
    when(workRepository.findById(work.getId())).thenReturn(Optional.of(work));
    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    when(contributionRepository.findSubmittedAfterSequence(eq(work), any(), captor.capture()))
        .thenReturn(List.of());

    service.getContributionDtos(work.getId(), null, 7);

    assertThat(captor.getValue().getPageSize()).isEqualTo(7);
  }

  @Test
  void getContributionDtos_unknownWorkId_throwsEntityNotFound() {
    UUID workId = UUID.randomUUID();
    when(workRepository.findById(workId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getContributionDtos(workId, null, 20))
        .isInstanceOf(EntityNotFoundException.class);
  }

  @Test
  void getContributionDto_found_returnsMappedDto() {
    Contribution c = Contribution.builder().id(UUID.randomUUID()).build();
    ContributionDto dto = ContributionDto.builder().id(c.getId()).build();
    when(contributionRepository.findSubmittedById(c.getId())).thenReturn(Optional.of(c));
    when(contributionMapper.toDto(c)).thenReturn(dto);

    assertThat(service.getContributionDto(c.getId())).isSameAs(dto);
  }

  @Test
  void getContributionDto_notFoundOrDraft_throwsEntityNotFound() {
    UUID id = UUID.randomUUID();
    when(contributionRepository.findSubmittedById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getContributionDto(id))
        .isInstanceOf(EntityNotFoundException.class);
  }
}
