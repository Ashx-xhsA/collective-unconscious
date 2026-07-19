package com.cu.collective_unconscious.controller;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.dtos.CreateWorkRequestDto;
import com.cu.collective_unconscious.dtos.UpdateWorkRequestDto;
import com.cu.collective_unconscious.dtos.WorkDetailDto;
import com.cu.collective_unconscious.dtos.WorkListItemDto;
import com.cu.collective_unconscious.security.UserDetail;
import com.cu.collective_unconscious.services.UserService;
import com.cu.collective_unconscious.services.WorkService;

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

@RestController
@RequestMapping(path = "/api/v1/works")
@RequiredArgsConstructor
public class WorkController {

  private final WorkService workService;
  private final UserService userService;

  @GetMapping
  public ResponseEntity<List<WorkListItemDto>> getAllWorks(
      @RequestParam(required = false) UUID tagId) {
    List<WorkListItemDto> works = workService.getWorkListItemDtos(tagId);
    return ResponseEntity.ok(works);
  }

  @PostMapping
  public ResponseEntity<WorkDetailDto> createWork(@Valid @RequestBody CreateWorkRequestDto dto,
      @AuthenticationPrincipal UserDetail principal) {
    User author = userService.getById(principal.getId());
    WorkDetailDto created = workService.createWork(dto, author);
    return ResponseEntity.status(HttpStatus.CREATED).body(created);
  }

  @GetMapping("/{id}")
  public ResponseEntity<WorkDetailDto> getWorkById(@PathVariable UUID id) {
    return ResponseEntity.ok(workService.getWorkDetailDto(id));
  }

  @PatchMapping("/{id}")
  public ResponseEntity<WorkDetailDto> updateWork(@PathVariable UUID id,
      @Valid @RequestBody UpdateWorkRequestDto dto) {
    return ResponseEntity.ok(workService.updateWork(id, dto));
  }
}
