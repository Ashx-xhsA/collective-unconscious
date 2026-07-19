package com.cu.collective_unconscious.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.cu.collective_unconscious.domain.entities.Contribution;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;
import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.security.UserDetail;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end integration test of the Contribution drafts surface: the real Spring Security filter
 * chain + ContributionController + ContributionServiceImpl, over MockMvc, against real Postgres.
 * Authenticates with a real {@link UserDetail} instance (not the generic Spring User) so
 * {@code @AuthenticationPrincipal UserDetail} in the controller binds correctly. Locks in: the
 * route requires login, drafts are scoped to the logged-in author, submitted contributions never
 * leak into the draft list, and the response flattens the parent work's title.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContributionControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired ContributionRepository contributionRepository;
  @Autowired WorkRepository workRepository;
  @Autowired UserRepository userRepository;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
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

  private Work persistWork(User author) {
    return workRepository.save(Work.builder()
        .title("The Long Story")
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(author)
        .build());
  }

  private Work persistWork(User author, WorkType workType, int maxWords, boolean completed) {
    return workRepository.save(Work.builder()
        .title("The Long Story")
        .preface("preface")
        .workType(workType)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(author)
        .maxWords(maxWords)
        .completed(completed)
        .build());
  }

  private void persistContribution(Work work, User author, boolean draft) {
    contributionRepository.save(Contribution.builder()
        .work(work)
        .author(author)
        .content("some content")
        .draft(draft)
        .build());
  }

  private Contribution persistDraft(Work work, User author, String title, String content) {
    return contributionRepository.save(Contribution.builder()
        .work(work)
        .author(author)
        .title(title)
        .content(content)
        .draft(true)
        .build());
  }

  private Contribution persistSubmitted(Work work, User author, String title, String content,
      int sequenceNumber) {
    return contributionRepository.save(Contribution.builder()
        .work(work)
        .author(author)
        .title(title)
        .content(content)
        .draft(false)
        .sequenceNumber(sequenceNumber)
        .build());
  }

  // --- GET /api/v1/contributions/drafts --------------------------------------

  @Test
  void getDrafts_withoutLogin_returns401() throws Exception {
    mockMvc.perform(get("/api/v1/contributions/drafts"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void getDrafts_returnsEmptyList_whenUserHasNoDrafts() throws Exception {
    User me = persistUser();

    mockMvc.perform(get("/api/v1/contributions/drafts").with(user(new UserDetail(me))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void getDrafts_returnsMyDraft_withFlattenedWorkTitle() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    persistContribution(work, me, true);

    mockMvc.perform(get("/api/v1/contributions/drafts").with(user(new UserDetail(me))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].workId").value(work.getId().toString()))
        .andExpect(jsonPath("$[0].workTitle").value("The Long Story"))
        .andExpect(jsonPath("$[0].content").value("some content"));
  }

  @Test
  void getDrafts_excludesSubmittedContributions() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    persistContribution(work, me, true);  // draft
    persistContribution(work, me, false); // submitted

    mockMvc.perform(get("/api/v1/contributions/drafts").with(user(new UserDetail(me))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)));
  }

  @Test
  void getDrafts_excludesOtherUsersDrafts() throws Exception {
    User me = persistUser();
    User other = persistUser();
    Work work = persistWork(other);
    persistContribution(work, other, true);

    mockMvc.perform(get("/api/v1/contributions/drafts").with(user(new UserDetail(me))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  // --- POST /api/v1/works/{workId}/contributions ------------------------------

  private String draftBody(String title, String content) throws Exception {
    java.util.Map<String, Object> body = new java.util.HashMap<>();
    body.put("title", title);
    body.put("content", content);
    return objectMapper.writeValueAsString(body);
  }

  @Test
  void createDraft_withoutLogin_returns401() throws Exception {
    User owner = persistUser();
    Work work = persistWork(owner);

    mockMvc.perform(post("/api/v1/works/" + work.getId() + "/contributions")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody("Chapter One", "once upon a time")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createDraft_withoutCsrfToken_returns403() throws Exception {
    User owner = persistUser();
    Work work = persistWork(owner);

    mockMvc.perform(post("/api/v1/works/" + work.getId() + "/contributions")
            .with(user(new UserDetail(persistUser())))
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody("Chapter One", "once upon a time")))
        .andExpect(status().isForbidden());
  }

  @Test
  void createDraft_validBody_returns201_andPersistsDraftWithNoSequenceNumber() throws Exception {
    User me = persistUser();
    Work work = persistWork(me); // NOVEL, default maxWords/maxParagraphs

    mockMvc.perform(post("/api/v1/works/" + work.getId() + "/contributions")
            .with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody("Chapter One", "once upon a time")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("Chapter One"))
        .andExpect(jsonPath("$.content").value("once upon a time"))
        .andExpect(jsonPath("$.sequenceNumber").doesNotExist());

    Contribution saved = contributionRepository.findAll().get(0);
    assertThat(saved.isDraft()).isTrue();
    assertThat(saved.getSequenceNumber()).isNull();
    assertThat(saved.getAuthor().getId()).isEqualTo(me.getId());
  }

  @Test
  void createDraft_unknownWorkId_returns404() throws Exception {
    mockMvc.perform(post("/api/v1/works/" + UUID.randomUUID() + "/contributions")
            .with(user(new UserDetail(persistUser()))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody("Chapter One", "once upon a time")))
        .andExpect(status().isNotFound());
  }

  @Test
  void createDraft_novelWithoutTitle_returns400() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);

    mockMvc.perform(post("/api/v1/works/" + work.getId() + "/contributions")
            .with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody(null, "once upon a time")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createDraft_contentExceedsMaxWords_returns400() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, WorkType.ARTICLE, 5, false);

    mockMvc.perform(post("/api/v1/works/" + work.getId() + "/contributions")
            .with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody(null, "way more than five characters")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createDraft_workAlreadyCompleted_returns409() throws Exception {
    User me = persistUser();
    Work work = persistWork(me, WorkType.ARTICLE, 1000, true);

    mockMvc.perform(post("/api/v1/works/" + work.getId() + "/contributions")
            .with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(draftBody(null, "too late")))
        .andExpect(status().isConflict());
  }

  // --- PATCH /api/v1/contributions/{id} ---------------------------------------

  private String updateBody(String title, String content) throws Exception {
    java.util.Map<String, Object> body = new java.util.HashMap<>();
    if (title != null) {
      body.put("title", title);
    }
    if (content != null) {
      body.put("content", content);
    }
    return objectMapper.writeValueAsString(body);
  }

  @Test
  void updateDraft_withoutLogin_returns401() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "old", "old content");

    mockMvc.perform(patch("/api/v1/contributions/" + draft.getId())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody(null, "hacked")))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void updateDraft_withoutCsrfToken_returns403() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "old", "old content");

    mockMvc.perform(patch("/api/v1/contributions/" + draft.getId())
            .with(user(new UserDetail(me)))
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody(null, "hacked")))
        .andExpect(status().isForbidden());
  }

  @Test
  void updateDraft_updatesTitleAndContent_returns200() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "old title", "old content");

    mockMvc.perform(patch("/api/v1/contributions/" + draft.getId())
            .with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody("new title", "new content")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("new title"))
        .andExpect(jsonPath("$.content").value("new content"));

    Contribution reloaded = contributionRepository.findById(draft.getId()).orElseThrow();
    assertThat(reloaded.getTitle()).isEqualTo("new title");
    assertThat(reloaded.getContent()).isEqualTo("new content");
  }

  @Test
  void updateDraft_alreadySubmitted_returns409_andDoesNotChangeContent() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution submitted = persistDraft(work, me, "title", "original content");
    submitted.setDraft(false);
    contributionRepository.save(submitted);

    mockMvc.perform(patch("/api/v1/contributions/" + submitted.getId())
            .with(user(new UserDetail(me))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody(null, "hacked")))
        .andExpect(status().isConflict());

    assertThat(contributionRepository.findById(submitted.getId()).orElseThrow().getContent())
        .isEqualTo("original content");
  }

  @Test
  void updateDraft_unknownId_returns404() throws Exception {
    mockMvc.perform(patch("/api/v1/contributions/" + UUID.randomUUID())
            .with(user(new UserDetail(persistUser()))).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(updateBody(null, "x")))
        .andExpect(status().isNotFound());
  }

  // --- POST /api/v1/contributions/{id}/submit ---------------------------------

  @Test
  void submit_withoutLogin_returns401() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "Chapter One", "some content");

    mockMvc.perform(post("/api/v1/contributions/" + draft.getId() + "/submit").with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void submit_withoutCsrfToken_returns403() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "Chapter One", "some content");

    mockMvc.perform(post("/api/v1/contributions/" + draft.getId() + "/submit")
            .with(user(new UserDetail(me))))
        .andExpect(status().isForbidden());
  }

  @Test
  void submit_firstSubmission_assignsSequenceOne_flipsDraftFalse_addsAuthorToWork() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "Chapter One", "some content");

    mockMvc.perform(post("/api/v1/contributions/" + draft.getId() + "/submit")
            .with(user(new UserDetail(me))).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sequenceNumber").value(1));

    Contribution reloaded = contributionRepository.findById(draft.getId()).orElseThrow();
    assertThat(reloaded.isDraft()).isFalse();
    assertThat(reloaded.getSequenceNumber()).isEqualTo(1);
    // work.authors is a LAZY collection with no open session after the request completes
    // (open-in-view=false), so check the join table directly instead of touching the entity.
    Integer joinRowCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM work_authors WHERE work_id = ? AND user_id = ?",
        Integer.class, work.getId(), me.getId());
    assertThat(joinRowCount).isEqualTo(1);
  }

  @Test
  void submit_secondSubmission_assignsSequenceTwo() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution first = persistDraft(work, me, "Chapter One", "some content");
    mockMvc.perform(post("/api/v1/contributions/" + first.getId() + "/submit")
            .with(user(new UserDetail(me))).with(csrf()))
        .andExpect(status().isOk());
    Contribution second = persistDraft(work, me, "Chapter Two", "more content");

    mockMvc.perform(post("/api/v1/contributions/" + second.getId() + "/submit")
            .with(user(new UserDetail(me))).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sequenceNumber").value(2));
  }

  @Test
  void submit_alreadySubmitted_returns409() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "Chapter One", "some content");
    mockMvc.perform(post("/api/v1/contributions/" + draft.getId() + "/submit")
            .with(user(new UserDetail(me))).with(csrf()))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/v1/contributions/" + draft.getId() + "/submit")
            .with(user(new UserDetail(me))).with(csrf()))
        .andExpect(status().isConflict());
  }

  @Test
  void submit_workCompletedAfterDraftWasCreated_returns409() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "Chapter One", "some content");
    work.setCompleted(true);
    workRepository.save(work);

    mockMvc.perform(post("/api/v1/contributions/" + draft.getId() + "/submit")
            .with(user(new UserDetail(me))).with(csrf()))
        .andExpect(status().isConflict());
  }

  @Test
  void submit_unknownId_returns404() throws Exception {
    mockMvc.perform(post("/api/v1/contributions/" + UUID.randomUUID() + "/submit")
            .with(user(new UserDetail(persistUser()))).with(csrf()))
        .andExpect(status().isNotFound());
  }

  // --- GET /api/v1/works/{workId}/contributions (cursor list) -----------------

  @Test
  void getContributions_isPubliclyAccessible_evenWithoutLogin() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    persistSubmitted(work, me, "Chapter One", "once upon a time", 1);

    mockMvc.perform(get("/api/v1/works/" + work.getId() + "/contributions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].content").value("once upon a time"))
        .andExpect(jsonPath("$[0].author.username").value(me.getUsername()));
  }

  @Test
  void getContributions_returnsOnlySubmitted_orderedBySequence() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    persistSubmitted(work, me, "Chapter Two", "second", 2);
    persistSubmitted(work, me, "Chapter One", "first", 1);
    persistDraft(work, me, "Chapter Three (draft)", "not yet submitted");

    mockMvc.perform(get("/api/v1/works/" + work.getId() + "/contributions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(jsonPath("$[0].sequenceNumber").value(1))
        .andExpect(jsonPath("$[1].sequenceNumber").value(2));
  }

  @Test
  void getContributions_afterSequence_returnsNextBatch() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    persistSubmitted(work, me, "Chapter One", "first", 1);
    persistSubmitted(work, me, "Chapter Two", "second", 2);

    mockMvc.perform(get("/api/v1/works/" + work.getId() + "/contributions")
            .param("afterSequence", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].sequenceNumber").value(2));
  }

  @Test
  void getContributions_limit_capsResultSize() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    for (int i = 1; i <= 3; i++) {
      persistSubmitted(work, me, "Chapter " + i, "content " + i, i);
    }

    mockMvc.perform(get("/api/v1/works/" + work.getId() + "/contributions")
            .param("limit", "2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));
  }

  @Test
  void getContributions_unknownWorkId_returns404() throws Exception {
    mockMvc.perform(get("/api/v1/works/" + UUID.randomUUID() + "/contributions"))
        .andExpect(status().isNotFound());
  }

  // --- GET /api/v1/contributions/{id} (single direct read) ---------------------

  @Test
  void getContribution_byId_isPubliclyAccessible_returnsFullBody() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution submitted = persistSubmitted(work, me, "Chapter One", "once upon a time", 1);

    mockMvc.perform(get("/api/v1/contributions/" + submitted.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Chapter One"))
        .andExpect(jsonPath("$.content").value("once upon a time"))
        .andExpect(jsonPath("$.author.username").value(me.getUsername()))
        .andExpect(jsonPath("$.author.id").exists());
  }

  @Test
  void getContribution_draftId_returns404() throws Exception {
    User me = persistUser();
    Work work = persistWork(me);
    Contribution draft = persistDraft(work, me, "still a draft", "content");

    mockMvc.perform(get("/api/v1/contributions/" + draft.getId()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getContribution_unknownId_returns404() throws Exception {
    mockMvc.perform(get("/api/v1/contributions/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}
