package com.cu.collective_unconscious.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.cu.collective_unconscious.domain.entities.Tag;
import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.Work;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.domain.entities.enums.TurnStyle;
import com.cu.collective_unconscious.domain.entities.enums.Visibility;
import com.cu.collective_unconscious.domain.entities.enums.WorkType;
import com.cu.collective_unconscious.domain.entities.enums.WritingMode;
import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.TicketRepository;
import com.cu.collective_unconscious.repository.TagRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.security.UserDetail;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end integration test of the Tag surface: the real Spring Security filter chain +
 * TagController + error mapping, over MockMvc, against real Postgres. GET is public (per
 * SecurityConfig), POST/DELETE require login + CSRF. {@code @Valid} on
 * {@link com.cu.collective_unconscious.controller.TagController#createTags} is what actually
 * enforces {@code CreateTagsRequest}'s constraints — this test locks that wiring in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TagControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired TagRepository tagRepository;
  @Autowired WorkRepository workRepository;
  @Autowired ContributionRepository contributionRepository;
  @Autowired UserRepository userRepository;
  @Autowired TicketRepository ticketRepository;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    // Respect FK order: contributions -> works (clears work_tags/work_authors) -> tags/users.
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

  private Work persistWorkTaggedWith(Tag tag) {
    User author = userRepository.save(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());
    return workRepository.save(Work.builder()
        .title("A Work")
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(Visibility.PUBLIC)
        .createdAuthor(author)
        .tags(Set.of(tag))
        .build());
  }

  private String createBody(Set<String> names) throws Exception {
    return objectMapper.writeValueAsString(Map.of("names", names));
  }

  // --- GET /api/v1/tags (public) -------------------------------------------

  @Test
  void getAllTags_isPubliclyAccessible_evenWithoutLogin() throws Exception {
    tagRepository.save(Tag.builder().name("fantasy").build());

    mockMvc.perform(get("/api/v1/tags"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("fantasy"))
        .andExpect(jsonPath("$[0].workCount").value(0));
  }

  @Test
  void getAllTags_returnsEmptyList_whenNoneExist() throws Exception {
    mockMvc.perform(get("/api/v1/tags"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray())
        .andExpect(jsonPath("$").isEmpty());
  }

  // --- POST /api/v1/tags ----------------------------------------------------

  @Test
  void createTags_withoutLogin_returns401() throws Exception {
    // CSRF is checked before authentication, so a valid token must be present here
    // to isolate the "requires login" rule from the separate CSRF rejection (403).
    mockMvc.perform(post("/api/v1/tags")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of("fantasy"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createTags_withoutCsrfToken_returns403() throws Exception {
    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of("fantasy"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void createTags_validNames_returns201_andPersists() throws Exception {
    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of("fantasy", "sci-fi"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));

    assertThat(tagRepository.findAll()).extracting(Tag::getName)
        .containsExactlyInAnyOrder("fantasy", "sci-fi");
  }

  @Test
  void createTags_duplicateName_isIdempotent_doesNotCreateSecondRow() throws Exception {
    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of("fantasy"))))
        .andExpect(status().isCreated());

    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of("fantasy"))))
        .andExpect(status().isCreated());

    assertThat(tagRepository.findByNameIn(Set.of("fantasy"))).hasSize(1);
  }

  @Test
  void createTags_emptyNames_returns400() throws Exception {
    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of())))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Validation failed"));
    assertThat(tagRepository.findAll()).isEmpty();
  }

  @Test
  void createTags_moreThanTenNames_returns400() throws Exception {
    Set<String> elevenNames = new java.util.HashSet<>();
    for (int i = 0; i < 11; i++) {
      elevenNames.add("tag" + i);
    }

    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(elevenNames)))
        .andExpect(status().isBadRequest());
    assertThat(tagRepository.findAll()).isEmpty();
  }

  @Test
  void createTags_nameWithIllegalCharacters_returns400() throws Exception {
    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of("fan@tasy!"))))
        .andExpect(status().isBadRequest());
    assertThat(tagRepository.findAll()).isEmpty();
  }

  @Test
  void createTags_nameTooLong_returns400() throws Exception {
    mockMvc.perform(post("/api/v1/tags")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody(Set.of("a".repeat(31)))))
        .andExpect(status().isBadRequest());
    assertThat(tagRepository.findAll()).isEmpty();
  }

  // --- DELETE /api/v1/tags/{id} ---------------------------------------------

  @Test
  void deleteTag_withoutLogin_returns401() throws Exception {
    // Same CSRF-before-auth ordering as createTags_withoutLogin_returns401 above.
    Tag tag = tagRepository.save(Tag.builder().name("fantasy").build());

    mockMvc.perform(delete("/api/v1/tags/{id}", tag.getId()).with(csrf()))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void deleteTag_unused_returns204_andRemovesIt() throws Exception {
    Tag tag = tagRepository.save(Tag.builder().name("fantasy").build());

    mockMvc.perform(delete("/api/v1/tags/{id}", tag.getId())
            .with(user(persistLoggedInUser())).with(csrf()))
        .andExpect(status().isNoContent());

    assertThat(tagRepository.findById(tag.getId())).isEmpty();
  }

  @Test
  void deleteTag_stillAttachedToAWork_returns409_andKeepsIt() throws Exception {
    Tag tag = tagRepository.save(Tag.builder().name("fantasy").build());
    persistWorkTaggedWith(tag);

    mockMvc.perform(delete("/api/v1/tags/{id}", tag.getId())
            .with(user(persistLoggedInUser())).with(csrf()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("can not delete tag with posts"));

    assertThat(tagRepository.findById(tag.getId())).isPresent();
  }

  @Test
  void deleteTag_unknownId_isNoOp_returns204() throws Exception {
    mockMvc.perform(delete("/api/v1/tags/{id}", UUID.randomUUID())
            .with(user(persistLoggedInUser())).with(csrf()))
        .andExpect(status().isNoContent());
  }
}
