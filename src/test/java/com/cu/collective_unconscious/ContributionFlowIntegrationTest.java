package com.cu.collective_unconscious;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.security.UserDetail;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end walk of the writing chain (plan §8, Phase 1 wrap-up): new draft -> edit draft ->
 * submit (draft flips, sequence assigned, author joins the work) -> read back both via the
 * per-work cursor list and the single-chapter direct read. Per-endpoint edge cases already live in
 * {@link com.cu.collective_unconscious.controller.ContributionControllerTest}; this test only pins
 * that the whole chain composes against real Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContributionFlowIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired WorkRepository workRepository;
  @Autowired ContributionRepository contributionRepository;
  @Autowired UserRepository userRepository;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbcTemplate;

  @BeforeEach
  void clean() {
    contributionRepository.deleteAll();
    workRepository.deleteAll();
    userRepository.deleteAll();
  }

  private UserDetail persistLoggedInUser() {
    User user = userRepository.save(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());
    return new UserDetail(user);
  }

  private Work persistWork(UUID authorId) {
    User author = userRepository.findById(authorId).orElseThrow();
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

  private String body(String title, String content) throws Exception {
    Map<String, Object> m = new HashMap<>();
    m.put("title", title);
    m.put("content", content);
    return objectMapper.writeValueAsString(m);
  }

  @Test
  void draftEditSubmitRead_composesEndToEnd_againstRealPostgres() throws Exception {
    UserDetail me = persistLoggedInUser();
    Work work = persistWork(me.getId());

    // 1. New draft: draft=true, no sequence number yet.
    String createResponse = mockMvc.perform(post("/api/v1/works/" + work.getId() + "/contributions")
            .with(user(me)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Chapter One", "once upon a time")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sequenceNumber").doesNotExist())
        .andReturn().getResponse().getContentAsString();
    String contributionId = objectMapper.readTree(createResponse).get("id").asText();

    // 2. Edit the draft (2nd+ "save draft" click).
    mockMvc.perform(patch("/api/v1/contributions/" + contributionId)
            .with(user(me)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(body("Chapter One", "once upon a midnight dreary")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("once upon a midnight dreary"));

    // 3. Submit: draft flips false, sequence starts at 1, author joins the work.
    mockMvc.perform(post("/api/v1/contributions/" + contributionId + "/submit")
            .with(user(me)).with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sequenceNumber").value(1));

    assertThat(contributionRepository.findById(UUID.fromString(contributionId))
        .orElseThrow().isDraft()).isFalse();
    Integer joinRowCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM work_authors WHERE work_id = ? AND user_id = ?",
        Integer.class, work.getId(), me.getId());
    assertThat(joinRowCount).isEqualTo(1);

    // 4. Read back via the per-work cursor list.
    mockMvc.perform(get("/api/v1/works/" + work.getId() + "/contributions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].sequenceNumber").value(1))
        .andExpect(jsonPath("$[0].content").value("once upon a midnight dreary"))
        .andExpect(jsonPath("$[0].author.username").value(me.getUsername()));

    // 5. Read back the single chapter directly.
    mockMvc.perform(get("/api/v1/contributions/" + contributionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Chapter One"))
        .andExpect(jsonPath("$.content").value("once upon a midnight dreary"));
  }
}
