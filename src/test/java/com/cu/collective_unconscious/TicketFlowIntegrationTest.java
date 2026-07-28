package com.cu.collective_unconscious;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
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

import com.cu.collective_unconscious.domain.entities.Ticket;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TicketStatus;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;
import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.security.UserDetail;
import com.cu.collective_unconscious.services.TicketService;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end rotation walks for the ticket state machine (plan §8) over the real HTTP stack +
 * Postgres. The per-endpoint contract lives in {@code TicketControllerTest}; this pins the
 * stateful multi-user flows: RobinRound A→B→A rotation with auto re-enqueue, the single-user
 * self-lock guard, FreeTurn's no-re-enqueue/manual-redraw, the submit gate + ownership, a real
 * WAITING-holder being blocked from drawing, and advanceWork() timeout self-healing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketFlowIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired WorkRepository workRepository;
  @Autowired UserRepository userRepository;
  @Autowired TicketRepository ticketRepository;
  @Autowired ContributionRepository contributionRepository;
  @Autowired ObjectMapper objectMapper;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
  @Autowired TicketService ticketService;

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

  private Work persistWork(User author, TurnStyle turnStyle) {
    return persistWork(author, turnStyle, null);
  }

  private Work persistWork(User author, TurnStyle turnStyle, Integer maxParticipants) {
    return workRepository.save(Work.builder()
        .title("The Long Story")
        .preface("preface")
        .workType(WorkType.ARTICLE) // no chapter title required, keeps drafts simple
        .turnStyle(turnStyle)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(author)
        .maxParticipants(maxParticipants)
        .build());
  }

  private String ticketsUrl(Work work) {
    return "/api/v1/works/" + work.getId() + "/tickets";
  }

  private void drawExpect(Work work, User u, int expectedStatus) throws Exception {
    mockMvc.perform(post(ticketsUrl(work)).with(user(new UserDetail(u))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().is(expectedStatus));
  }

  private String createDraft(Work work, User u, String content) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("content", content);
    String resp = mockMvc.perform(
            post("/api/v1/works/" + work.getId() + "/contributions")
                .with(user(new UserDetail(u))).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();
    return objectMapper.readTree(resp).get("id").asText();
  }

  private void submitExpect(String contributionId, User u, int expectedStatus) throws Exception {
    mockMvc.perform(post("/api/v1/contributions/" + contributionId + "/submit")
            .with(user(new UserDetail(u))).with(csrf()))
        .andExpect(status().is(expectedStatus));
  }

  // --- RobinRound (round-based) ----------------------------------------------

  @Test
  void robinRound_drawAddsDrawerToAuthors_evenBeforeSubmitting() throws Exception {
    User a = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND);

    drawExpect(work, a, 201); // joined, but hasn't submitted anything yet

    Integer authorRows = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM work_authors WHERE work_id = ? AND user_id = ?",
        Integer.class, work.getId(), a.getId());
    assertThat(authorRows).isEqualTo(1); // join == becoming a participant/author

    User b = persistUser();
    drawExpect(work,b,201);

    Integer authorRows2 = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM work_authors WHERE work_id = ? AND user_id = ?",
            Integer.class, work.getId(), b.getId());
    assertThat(authorRows2).isEqualTo(1); // join == becoming a participant/author

  }

  @Test
  void robinRound_rotatesWithPreGeneratedLookahead() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND);

    drawExpect(work, a, 201); // #1 ACTIVE (a). N=1 -> no lookahead yet.
    drawExpect(work, b, 201); // b joins -> #2(b) WAITING; check() queues the next round -> #3(a).

    // a still ACTIVE; one full round (b, then a) queued ahead.
    mockMvc.perform(get(ticketsUrl(work)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
        .andExpect(jsonPath("$[0].status").value("ACTIVE"))
        .andExpect(jsonPath("$[0].holderId").value(a.getId().toString()))
        .andExpect(jsonPath("$[1].status").value("WAITING"))
        .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
        .andExpect(jsonPath("$[2].status").value("WAITING"))
        .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()));

    // a writes + submits -> #1 CONSUMED, turn to b (#2 ACTIVE); check() tops up -> #4(b).
    String aDraft = createDraft(work, a, "chapter by a");
    submitExpect(aDraft, a, 200);

    mockMvc.perform(get(ticketsUrl(work)))
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(4)))
        .andExpect(jsonPath("$[0].status").value("CONSUMED"))
        .andExpect(jsonPath("$[0].contributionId").value(aDraft))
        .andExpect(jsonPath("$[1].status").value("ACTIVE"))
        .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
        .andExpect(jsonPath("$[2].status").value("WAITING"))
        .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()))
        .andExpect(jsonPath("$[3].status").value("WAITING"))
        .andExpect(jsonPath("$[3].holderId").value(b.getId().toString()));

    // b submits -> turn back to a (#3 ACTIVE). Rotation A->B->A, nobody manually re-draws.
    String bDraft = createDraft(work, b, "chapter by b");
    submitExpect(bDraft, b, 200);

    mockMvc.perform(get(ticketsUrl(work) + "/current").with(user(new UserDetail(a))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itIsMyTurn").value(true))
        .andExpect(jsonPath("$.activeTicketNumber").value(3));
  }

  @Test
  void robinRound_singleUser_waitsForSecondPerson() throws Exception {
    User a = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND);

    drawExpect(work, a, 201); // #1 ACTIVE. N=1, self-lock -> no lookahead.
    String aDraft = createDraft(work, a, "first");
    submitExpect(aDraft, a, 200); // #1 CONSUMED -> no activation, waits

    // a already joined (a participant) and is alone -> can't draw again -> the work waits.
    drawExpect(work, a, 409);
    mockMvc.perform(get(ticketsUrl(work) + "/current").with(user(new UserDetail(a))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itIsMyTurn").value(false))
        .andExpect(jsonPath("$.activeHolderId").doesNotExist());

    // A second person joins -> takes the turn; rotation resumes automatically (no re-draw by a).
    User b = persistUser();
    drawExpect(work, b, 201);
    mockMvc.perform(get(ticketsUrl(work) + "/current").with(user(new UserDetail(b))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.itIsMyTurn").value(true));
    mockMvc.perform(get(ticketsUrl(work)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(4)))
            .andExpect(jsonPath("$[0].status").value("CONSUMED"))
            .andExpect(jsonPath("$[0].contributionId").value(aDraft))
            .andExpect(jsonPath("$[1].status").value("ACTIVE"))
            .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
            .andExpect(jsonPath("$[2].status").value("WAITING"))
            .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()))
            .andExpect(jsonPath("$[3].status").value("WAITING"))
            .andExpect(jsonPath("$[3].holderId").value(b.getId().toString()));

    // a third person joins
    User c = persistUser();
    drawExpect(work,c,201);
    mockMvc.perform(get(ticketsUrl(work)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(5)))
            .andExpect(jsonPath("$[0].status").value("CONSUMED"))
            .andExpect(jsonPath("$[0].contributionId").value(aDraft))
            .andExpect(jsonPath("$[1].status").value("ACTIVE"))
            .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
            .andExpect(jsonPath("$[2].status").value("WAITING"))
            .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()))
            .andExpect(jsonPath("$[3].status").value("WAITING"))
            .andExpect(jsonPath("$[3].holderId").value(b.getId().toString()))
            .andExpect(jsonPath("$[4].status").value("WAITING"))
            .andExpect(jsonPath("$[4].holderId").value(c.getId().toString()));

    // b submit
    submitExpect(createDraft(work,b,"submit"),b,200);
    mockMvc.perform(get(ticketsUrl(work)))
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(6)))
            .andExpect(jsonPath("$[0].status").value("CONSUMED"))
            .andExpect(jsonPath("$[0].contributionId").value(aDraft))
            .andExpect(jsonPath("$[1].status").value("CONSUMED"))
            .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
            .andExpect(jsonPath("$[2].status").value("ACTIVE"))
            .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()))
            .andExpect(jsonPath("$[3].status").value("WAITING"))
            .andExpect(jsonPath("$[3].holderId").value(b.getId().toString()))
            .andExpect(jsonPath("$[4].status").value("WAITING"))
            .andExpect(jsonPath("$[4].holderId").value(c.getId().toString()))
            .andExpect(jsonPath("$[5].status").value("WAITING"))
            .andExpect(jsonPath("$[5].holderId").value(a.getId().toString()));

  }

  @Test
  void robinRound_participantCannotDrawTwice_returns409() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND);

    drawExpect(work, a, 201); // a joins
    drawExpect(work, b, 201); // b joins
    drawExpect(work, b, 409); // b is already in the rotation -> can't join again
    drawExpect(work,a,409);
  }

  @Test
  void maxParticipants_blocksBrandNewJoinerWhenFull() throws Exception {
    User a = persistUser();
    User b = persistUser();
    User c = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND, 2); // cap = 2 participants

    drawExpect(work, a, 201); // participant 1
    drawExpect(work, b, 201); // participant 2 -> at cap
    drawExpect(work, c, 409); // c is new and the cap is reached -> rejected
  }

  // --- FreeTurn ---------------------------------------------------------------

  @Test
  void freeTurn_noAutoReEnqueue_andManualRedrawWhenNotTail() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, TurnStyle.FREETURN);

    drawExpect(work, a, 201); // #1 ACTIVE (a)
    String aDraft = createDraft(work, a, "chapter by a");
    submitExpect(aDraft, a, 200); // #1 CONSUMED, NO re-enqueue (FreeTurn) -> queue idle

    // No ACTIVE: FreeTurn never auto-continues.
    mockMvc.perform(get(ticketsUrl(work)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].status").value("CONSUMED"));

    drawExpect(work, b, 201); // b draws -> #2 ACTIVE (tail was consumed, so b is eligible)
    // a draws again: tail is b (#2), not a -> allowed -> #3 WAITING.
    drawExpect(work, a, 201);

    mockMvc.perform(get(ticketsUrl(work)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
        .andExpect(jsonPath("$[1].status").value("ACTIVE"))
        .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
        .andExpect(jsonPath("$[2].status").value("WAITING"))
        .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()));
  }

  @Test
  void freeTurn_drawRule_callerIsTail_returns409() throws Exception {
    User a = persistUser();
    Work work = persistWork(a, TurnStyle.FREETURN);

    drawExpect(work, a, 201);  // a holds the newest ticket (its ACTIVE #1)
    drawExpect(work, a, 409);  // drawing again while being the most recent -> rejected

    User b = persistUser();
    drawExpect(work,b,201);
    drawExpect(work,b,409);

  }

  @Test
  void freeTurn_cannotRedrawImmediatelyAfterOwnSubmit_untilSomeoneElseGoes() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, TurnStyle.FREETURN);

    drawExpect(work, a, 201);                       // #1 ACTIVE (a)
    submitExpect(createDraft(work, a, "first"), a, 200); // #1 CONSUMED — a is the most recent

    drawExpect(work, a, 409);  // a's own CONSUMED is the newest -> no two in a row
    drawExpect(work, b, 201);  // b may draw (newest is a) -> #2 ACTIVE
    drawExpect(work, a, 201);  // now a may draw again (newest is b) -> #3 WAITING
  }

  // --- submit gate ------------------------------------------------------------

  @Test
  void submit_withoutHoldingActiveTicket_returns409() throws Exception {
    User a = persistUser();
    User c = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND);

    drawExpect(work, a, 201); // a holds the ACTIVE ticket, not c
    String cDraft = createDraft(work, c, "c wrote this without the turn");

    submitExpect(cDraft, c, 409); // not c's turn
  }

  @Test
  void submit_someoneElsesDraft_returns403() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND);

    drawExpect(work, a, 201);
    String aDraft = createDraft(work, a, "a's draft");

    submitExpect(aDraft, b, 403); // b is not the author -> forbidden (before any ticket check)
  }

  // --- timeout, read-time dynamic judgment ------------------------------------

  @Test
  void timeout_onAdvance_expiresActiveAndActivatesNext() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, TurnStyle.FREETURN); // FreeTurn: expiry just activates next, no re-enqueue

    drawExpect(work, a, 201); // #1 ACTIVE (a)
    drawExpect(work, b, 201); // #2 WAITING (b)

    // Force a's ACTIVE ticket to have already expired (instead of waiting a real hour).
    Ticket active = ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE).orElseThrow();
    active.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    ticketRepository.save(active);

    // Reads no longer self-heal; advanceWork() (what the scheduler calls) does the rotation.
    ticketService.advanceWork(work.getId()); // #1 -> EXPIRED, #2 (b) -> ACTIVE

    mockMvc.perform(get(ticketsUrl(work)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$[0].status").value("EXPIRED"))
        .andExpect(jsonPath("$[0].holderId").value(a.getId().toString()))
        .andExpect(jsonPath("$[1].status").value("ACTIVE"))
        .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()));

    assertThat(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .get().extracting(t -> t.getHolder().getId()).isEqualTo(b.getId());
  }

  @Test
  void timeout_onAdvance_expiresActiveAndActivatesNext_RobinRound() throws Exception {
    User a = persistUser();
    User b = persistUser();
    Work work = persistWork(a, TurnStyle.ROBINROUND); // RobinRound: expiry activates next + tops up lookahead

    drawExpect(work, a, 201); // #1 ACTIVE (a)
    drawExpect(work, b, 201); // #2 WAITING (b); b's draw already generated the #3 (a) lookahead

    // Pure read: the one-round lookahead (#3 a) was created during b's draw, not by this read.
    mockMvc.perform(get(ticketsUrl(work)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$[0].holderId").value(a.getId().toString()))
            .andExpect(jsonPath("$[1].status").value("WAITING"))
            .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
            .andExpect(jsonPath("$[2].status").value("WAITING"))
            .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()));


    // Force a's ACTIVE ticket to have already expired (instead of waiting a real hour).
    Ticket active = ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE).orElseThrow();
    active.setExpiresAt(LocalDateTime.now().minusMinutes(1));
    ticketRepository.save(active);

    // advanceWork(): #1 -> EXPIRED, #2 (b) -> ACTIVE, and top up the lookahead with #4 (b).
    ticketService.advanceWork(work.getId());

    mockMvc.perform(get(ticketsUrl(work)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(4)))
        .andExpect(jsonPath("$[0].status").value("EXPIRED"))
        .andExpect(jsonPath("$[0].holderId").value(a.getId().toString()))
        .andExpect(jsonPath("$[1].status").value("ACTIVE"))
        .andExpect(jsonPath("$[1].holderId").value(b.getId().toString()))
        .andExpect(jsonPath("$[2].status").value("WAITING"))
        .andExpect(jsonPath("$[2].holderId").value(a.getId().toString()))
        .andExpect(jsonPath("$[3].status").value("WAITING"))
        .andExpect(jsonPath("$[3].holderId").value(b.getId().toString()));

    assertThat(ticketRepository.findFirstByWorkAndStatus(work, TicketStatus.ACTIVE))
        .get().extracting(t -> t.getHolder().getId()).isEqualTo(b.getId());
  }
}