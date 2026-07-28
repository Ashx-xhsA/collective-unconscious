package com.cu.collective_unconscious.services.impl;

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
import com.cu.collective_unconscious.services.ContributionService;
import com.cu.collective_unconscious.services.TicketService;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.BreakIterator;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ContributionServiceImpl implements ContributionService {
  private final ContributionRepository contributionRepository;
  private final ContributionMapper contributionMapper;
  private final WorkRepository workRepository;
  private final TicketService ticketService;

  // readOnly = true keeps the Hibernate session open through the mapping below,
  // so contributionMapper can read work.getTitle() without LazyInitializationException.
  @Transactional(readOnly = true)
  @Override
  public List<ContributionDraftDto> getDraftContributionDtos(User user) {
    return contributionRepository.findDraftsWithWork(user, true)
        .stream()
        .map(contributionMapper::toDraftDto)
        .toList();
  }

  @Transactional
  @Override
  public ContributionDraftDto createDraft(UUID workId, CreateContributionRequestDto dto, User author) {
    Work work = workRepository.findById(workId)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + workId));

    validateContent(work, dto.getTitle(), dto.getContent());

    // Article contributions never carry a title, even if one was sent (§4.5/§2.4).
    String title = work.getWorkType() == WorkType.NOVEL ? dto.getTitle() : null;

    Contribution contribution = Contribution.builder()
        .work(work)
        .author(author)
        .title(title)
        .content(dto.getContent())
        .draft(true)
        .build();

    Contribution saved = contributionRepository.save(contribution);
    return contributionMapper.toDraftDto(saved);
  }

  // TODO(auth): once write permissions land, only the contribution's own author may call this.
  @Transactional
  @Override
  public ContributionDraftDto updateDraft(UUID id, UpdateContributionRequestDto dto, User user) {
    Contribution contribution = contributionRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("Contribution not found with id " + id));

    // Submitted content is logically deleted, not freely edited (§2.4) — this route only touches
    // still-draft rows.
    if (!contribution.isDraft()) {
      throw new IllegalStateException("Contribution is already submitted and can no longer be edited as a draft");
    }

    if (dto.getTitle() != null) {
      contribution.setTitle(dto.getTitle());
    }
    if (dto.getContent() != null) {
      contribution.setContent(dto.getContent());
    }

    Contribution saved = contributionRepository.save(contribution);
    return contributionMapper.toDraftDto(saved);
  }

  // The single atomic entry point that turns a draft into formal, ordered content (§3.3).
  // Same-work submits are serialized by the ACTIVE ticket + fencing and by the work lock below, so
  // the max+1 sequence_number can't collide concurrently.
  @Transactional
  @Override
  public ContributionDraftDto submit(UUID id, User user) {
    Contribution contribution = contributionRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("Contribution not found with id " + id));

    if (!contribution.isDraft()) {
      throw new IllegalStateException("Contribution is already submitted");
    }

    // Only the draft's own author may submit it.
    if (!contribution.getAuthor().equals(user)) {
      throw new AccessDeniedException("You may only submit your own draft");
    }

    Work work = contribution.getWork();

    // Lock the aggregate root before any ticket/sequence mutation, so this submit serializes with
    // draws and the scheduler on the same work (same row as findByIdForUpdate elsewhere).
    workRepository.findByIdForUpdate(work.getId())
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + work.getId()));

    // Ticket fencing: confirm the caller holds the ACTIVE, unexpired ticket, then consume it,
    // re-enqueue (RobinRound) and hand the turn onward — all in this same transaction, so if any
    // later step throws, the consumption rolls back too. Done before the content work so a caller
    // who isn't up doesn't waste it (throws 409 if it's not their turn).
    ticketService.consumeActiveTicketForSubmit(work, user, contribution);

    validateContent(work, contribution.getTitle(), contribution.getContent());

    Integer maxSequence = contributionRepository.findMaxSequenceNumber(work);
    int nextSequence = (maxSequence == null) ? 1 : maxSequence + 1;

    contribution.setSequenceNumber(nextSequence);
    contribution.setDraft(false);
    work.getAuthors().add(contribution.getAuthor());

    Contribution saved = contributionRepository.save(contribution);
    return contributionMapper.toDraftDto(saved);
  }

  @Transactional(readOnly = true)
  @Override
  public List<ContributionDto> getContributionDtos(UUID workId, Integer afterSequence, int limit) {
    Work work = workRepository.findById(workId)
        .orElseThrow(() -> new EntityNotFoundException("Work not found with id " + workId));
    return contributionRepository
        .findSubmittedAfterSequence(work, afterSequence, PageRequest.of(0, limit))
        .stream()
        .map(contributionMapper::toDto)
        .toList();
  }

  @Transactional(readOnly = true)
  @Override
  public ContributionDto getContributionDto(UUID id) {
    Contribution contribution = contributionRepository.findSubmittedById(id)
        .orElseThrow(() -> new EntityNotFoundException("Contribution not found with id " + id));
    return contributionMapper.toDto(contribution);
  }

  // Shared by createDraft (early feedback) and submit (hard gate) per §4.5. Limits live on the
  // Work, not static annotations, since each work sets its own maxWords/maxParagraphs.
  private void validateContent(Work work, String title, String content) {
    if (work.isCompleted()) {
      throw new IllegalStateException("Work is already completed; no further contributions may be submitted");
    }
    int charCount = countGraphemes(content);
    if (charCount > work.getMaxWords()) {
      throw new IllegalArgumentException("Content exceeds max words (" + work.getMaxWords() + ")");
    }
    int paragraphCount = countParagraphs(content);
    if (paragraphCount > work.getMaxParagraphs()) {
      throw new IllegalArgumentException("Content exceeds max paragraphs (" + work.getMaxParagraphs() + ")");
    }
    if (work.getWorkType() == WorkType.NOVEL && (title == null || title.isBlank())) {
      throw new IllegalArgumentException("Novel contributions require a non-blank title");
    }
  }

  // "字数" = character count, language-agnostic (2026-07-18 decision, §4.5): counts extended
  // grapheme clusters (what a human sees as one character), not UTF-16 code units or code points —
  // so combining marks (café) and multi-codepoint emoji count as one each.
  private int countGraphemes(String text) {
    BreakIterator iterator = BreakIterator.getCharacterInstance();
    iterator.setText(text);
    int count = 0;
    for (int end = iterator.next(); end != BreakIterator.DONE; end = iterator.next()) {
      count++;
    }
    return count;
  }

  // Paragraphs are runs of text separated by one-or-more newlines; blank segments don't count.
  private int countParagraphs(String text) {
    return (int) Arrays.stream(text.split("\n+")).filter(p -> !p.isBlank()).count();
  }
}
