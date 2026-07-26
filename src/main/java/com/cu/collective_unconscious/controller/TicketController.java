package com.cu.collective_unconscious.controller;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.dtos.CurrentTurnDto;
import com.cu.collective_unconscious.dtos.DrawTicketRequestDto;
import com.cu.collective_unconscious.dtos.TicketDto;
import com.cu.collective_unconscious.security.UserDetail;
import com.cu.collective_unconscious.services.TicketService;
import com.cu.collective_unconscious.services.UserService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

// Full paths per method (no class-level prefix), matching ContributionController: tickets are
// nested under /works/{workId}. Draw + current require login (@AuthenticationPrincipal); the queue
// list is guest-readable, so it takes no principal.
@RequiredArgsConstructor
@RestController
public class TicketController {
  private final UserService userService;
  private final TicketService ticketService;

  @PostMapping(path = "/api/v1/works/{workId}/tickets")
  public ResponseEntity<TicketDto> draw(@PathVariable UUID workId,
      @RequestBody(required = false) DrawTicketRequestDto dto,
      @AuthenticationPrincipal UserDetail principal) {
    User user = userService.getById(principal.getId());
    String accessCode = (dto == null) ? null : dto.getAccessCode();
    TicketDto ticket = ticketService.drawTicket(workId, user, accessCode);
    return ResponseEntity.status(HttpStatus.CREATED).body(ticket);
  }

  @GetMapping(path = "/api/v1/works/{workId}/tickets")
  public ResponseEntity<List<TicketDto>> list(@PathVariable UUID workId) {
    return ResponseEntity.ok(ticketService.listTickets(workId));
  }

  @GetMapping(path = "/api/v1/works/{workId}/tickets/current")
  public ResponseEntity<CurrentTurnDto> current(@PathVariable UUID workId,
      @AuthenticationPrincipal UserDetail principal) {
    User user = userService.getById(principal.getId());
    return ResponseEntity.ok(ticketService.getCurrentTurn(workId, user));
  }
}
