package com.cu.collective_unconscious.controller;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.dtos.ContributionDraftDto;
import com.cu.collective_unconscious.dtos.ContributionDto;
import com.cu.collective_unconscious.dtos.CreateContributionRequestDto;
import com.cu.collective_unconscious.dtos.UpdateContributionRequestDto;
import com.cu.collective_unconscious.services.ContributionService;
import com.cu.collective_unconscious.services.UserService;

import com.cu.collective_unconscious.security.UserDetail;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// No class-level @RequestMapping: Spring always concatenates a class-level prefix with each
// method's path, so a method can never "escape" it to an unrelated path like /api/v1/works/... .
// Decision 4 wants every contribution-related route (including ones nested under /works/{workId})
// on this one controller, so every method below spells out its own full path instead.
@RequiredArgsConstructor
@RestController
public class ContributionController {
  private final UserService userService;
  private final ContributionService contributionService;

  @GetMapping(path = "/api/v1/contributions/drafts")
  public ResponseEntity<List<ContributionDraftDto>> getDrafts(
          @AuthenticationPrincipal UserDetail principal) {
    User loggedInUser = userService.getById(principal.getId());
    List<ContributionDraftDto> drafts = contributionService.getDraftContributionDtos(loggedInUser);
    return ResponseEntity.ok(drafts);
  }

  @PostMapping(path = "/api/v1/works/{workId}/contributions")
  public ResponseEntity<ContributionDraftDto> createDraft(@PathVariable UUID workId,
      @Valid @RequestBody CreateContributionRequestDto dto,
      @AuthenticationPrincipal UserDetail principal) {
    User author = userService.getById(principal.getId());
    ContributionDraftDto created = contributionService.createDraft(workId, dto, author);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @PatchMapping(path = "/api/v1/contributions/{id}")
  public ResponseEntity<ContributionDraftDto> updateDraft(@PathVariable UUID id,
      @Valid @RequestBody UpdateContributionRequestDto dto,
      @AuthenticationPrincipal UserDetail principal) {
    User user = userService.getById(principal.getId());
    ContributionDraftDto updated = contributionService.updateDraft(id, dto, user);
    return ResponseEntity.ok(updated);
  }

  @PostMapping(path = "/api/v1/contributions/{id}/submit")
  public ResponseEntity<ContributionDraftDto> submit(@PathVariable UUID id,
      @AuthenticationPrincipal UserDetail principal) {
    User user = userService.getById(principal.getId());
    ContributionDraftDto submitted = contributionService.submit(id, user);
    return ResponseEntity.ok(submitted);
  }

  @GetMapping(path = "/api/v1/works/{workId}/contributions")
  public ResponseEntity<List<ContributionDto>> getContributions(@PathVariable UUID workId,
      @RequestParam(required = false) Integer afterSequence,
      @RequestParam(defaultValue = "20") int limit) {
    List<ContributionDto> contributions =
        contributionService.getContributionDtos(workId, afterSequence, limit);
    return ResponseEntity.ok(contributions);
  }

  @GetMapping(path = "/api/v1/contributions/{id}")
  public ResponseEntity<ContributionDto> getContribution(@PathVariable UUID id) {
    return ResponseEntity.ok(contributionService.getContributionDto(id));
  }
}
