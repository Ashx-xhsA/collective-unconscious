package com.cu.collective_unconscious.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;
import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.security.UserDetail;

import tools.jackson.databind.ObjectMapper;

/**
 * Integration test of the Ticket HTTP surface: real Spring Security filter chain + TicketController
 * + TicketServiceImpl over MockMvc against real Postgres. Pins the per-endpoint contract — draw
 * requires login + CSRF, the queue list is guest-readable, and the current-turn snapshot is
 * login-only — plus status codes (201/200/401/403/404/409). Full multi-user rotation lives in
 * {@code TicketFlowIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired WorkRepository workRepository;
  @Autowired UserRepository userRepository;
  @Autowired TicketRepository ticketRepository;
  @Autowired ContributionRepository contributionRepository;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    ticketRepository.deleteAll();
    contributionRepository.deleteAll();
    workRepository.deleteAll();
    userRepository.deleteAll();
  }

  private User persistUser() {
    return userRepository.save(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());
  }

  private Work persistWork(User author, String accessCode) {
    return workRepository.save(Work.builder()
        .title("The Long Story")
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .accessCode(accessCode)
        .createdAuthor(author)
        .build());
  }

  private String drawBody(String accessCode) throws Exception {
    Map<String, Object> body = new HashMap<>();
    if (accessCode != null) {
      body.put("accessCode", accessCode);
    }
    return objectMapper.writeValueAsString(body);
  }

  private String ticketsUrl(UUID workId) {
    return "/api/v1/works/" + workId + "/tickets";
  }

  // --- POST /tickets (draw) ---------------------------------------------------

  @Test
  void draw_withoutLogin_returns401() throws Exception {
    Work work = persistWork(persistUser(), null);

    mockMvc.perform(post(ticketsUrl(work.getId())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void draw_withoutCsrf_returns403() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, null);

    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me)))
            .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)))
        .andExpect(status().isForbidden());
  }

  @Test
  void draw_firstOnIdleQueue_returns201_withActiveTicketForCaller() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, null);

    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.ticketNumber").value(1))
        .andExpect(jsonPath("$.status").value("ACTIVE"))     // idle queue -> auto-activated
        .andExpect(jsonPath("$.holderId").value(me.getId().toString()))
        .andExpect(jsonPath("$.expiresAt").exists());
  }

  @Test
  void draw_unknownWork_returns404() throws Exception {
    mockMvc.perform(post(ticketsUrl(UUID.randomUUID()))
            .with(user(new UserDetail(persistUser()))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)))
        .andExpect(status().isNotFound());
  }

  @Test
  void draw_accessCodeMissing_returns403() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, "secret");

    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)))
        .andExpect(status().isForbidden());
  }

  @Test
  void draw_accessCodeWrong_returns403() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, "secret");

    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody("nope")))
        .andExpect(status().isForbidden());
  }

  @Test
  void draw_accessCodeCorrect_returns201() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, "secret");

    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody("secret")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  void draw_robinRound_sameUserDrawsTwice_secondReturns409() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, null);

    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)))
        .andExpect(status().isCreated());

    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)))
        .andExpect(status().isConflict()); // already holds the ACTIVE ticket
  }

  // --- GET /tickets (queue list, guest-readable) ------------------------------

  @Test
  void list_isGuestReadable_returnsWholeQueue() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, null);
    // a draws (ACTIVE), b draws (WAITING) — no login on the GET below (guest).
    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(a))).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)));
    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(b))).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)));

    // RobinRound pre-generates one round of lookahead, so after a(ACTIVE) + b(WAITING) the queue
    // also has a's next WAITING (#3). A guest can read the whole thing.
    mockMvc.perform(get(ticketsUrl(work.getId())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
        .andExpect(jsonPath("$[0].ticketNumber").value(1))
        .andExpect(jsonPath("$[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$[0].holderId").value(a.getId().toString()))
        .andExpect(jsonPath("$[1].ticketNumber").value(2))
        .andExpect(jsonPath("$[1].status").value("WAITING"))
        .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
        .andExpect(jsonPath("$[2].ticketNumber").value(3))
        .andExpect(jsonPath("$[2].status").value("WAITING"))
        .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()));
  }

  @Test
  void list_unknownWork_returns404() throws Exception {
    mockMvc.perform(get(ticketsUrl(UUID.randomUUID())))
        .andExpect(status().isNotFound());
  }

  // --- GET /tickets/current (login-only snapshot) -----------------------------

  @Test
  void current_withoutLogin_returns401() throws Exception {
    Work work = persistWork(persistUser(), null);

    mockMvc.perform(get(ticketsUrl(work.getId()) + "/current"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void current_withLogin_reflectsItIsMyTurn() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, null);
    mockMvc.perform(post(ticketsUrl(work.getId())).with(user(new UserDetail(me))).with(csrf())
        .contentType(MediaType.APPLICATION_JSON).content(drawBody(null)));

    mockMvc.perform(get(ticketsUrl(work.getId()) + "/current").with(user(new UserDetail(me))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itIsMyTurn").value(true))
        .andExpect(jsonPath("$.activeHolderId").value(me.getId().toString()))
        .andExpect(jsonPath("$.activeTicketNumber").value(1));
  }
}
