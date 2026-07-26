package com.cu.collective_unconscious;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
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
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.repository.TagRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.security.UserDetail;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end walk of the "create a work" chain (plan §8, Phase 1 wrap-up): create with tags ->
 * read detail -> restricted update (completed/accessCode only). Per-endpoint edge cases already
 * live in {@link com.cu.collective_unconscious.controller.WorkControllerTest}; this test only pins
 * that the whole chain composes against real Postgres and that the update stays narrowed to its
 * two allowed fields (2026-07-17 decision) across a single work's lifecycle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkFlowIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired WorkRepository workRepository;
  @Autowired TagRepository tagRepository;
  @Autowired ContributionRepository contributionRepository;
  @Autowired UserRepository userRepository;
  @Autowired TicketRepository ticketRepository;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    ticketRepository.deleteAll();
    contributionRepository.deleteAll();
    workRepository.deleteAll();
    tagRepository.deleteAll();
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

  @Test
  void createReadUpdate_composesEndToEnd_againstRealPostgres() throws Exception {
    UserDetail me = persistLoggedInUser();

    Map<String, Object> createBody = Map.of(
        "title", "The Long Story",
        "preface", "Once upon a time",
        "workType", "NOVEL",
        "turnStyle", "ROBINROUND",
        "writingMode", "DEFAULT",
        "tagNames", Set.of("fantasy"));

    String createResponse = mockMvc.perform(post("/api/v1/works")
            .with(user(me)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(createBody)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("The Long Story"))
        .andExpect(jsonPath("$.workType").value("NOVEL"))
        .andExpect(jsonPath("$.tags[0].name").value("fantasy"))
        .andExpect(jsonPath("$.completed").value(false))
        .andReturn().getResponse().getContentAsString();
    String workId = objectMapper.readTree(createResponse).get("id").asText();

    // Read: the detail endpoint (games/tags/workType) reflects exactly what was created.
    mockMvc.perform(get("/api/v1/works/" + workId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("The Long Story"))
        .andExpect(jsonPath("$.turnStyle").value("ROBINROUND"))
        .andExpect(jsonPath("$.tags[0].name").value("fantasy"))
        .andExpect(jsonPath("$.createdAuthor.username").value(me.getUsername()));

    // Update: only completed/accessCode change; the key enums from creation are untouched.
    mockMvc.perform(patch("/api/v1/works/" + workId)
            .with(user(me)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"completed\":true,\"accessCode\":\"letmein\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completed").value(true))
        .andExpect(jsonPath("$.requiresAccessCode").value(true))
        .andExpect(jsonPath("$.workType").value("NOVEL"))
        .andExpect(jsonPath("$.turnStyle").value("ROBINROUND"))
        .andExpect(jsonPath("$.title").value("The Long Story"));

    var reloaded = workRepository.findById(UUID.fromString(workId)).orElseThrow();
    assertThat(reloaded.isCompleted()).isTrue();
    assertThat(reloaded.getAccessCode()).isEqualTo("letmein");
    assertThat(reloaded.getWorkType().name()).isEqualTo("NOVEL"); // key enum: immutable after creation
  }
}
