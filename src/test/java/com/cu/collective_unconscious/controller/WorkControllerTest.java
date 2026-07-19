package com.cu.collective_unconscious.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.cu.collective_unconscious.repository.TagRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import com.cu.collective_unconscious.security.UserDetail;

import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end integration test of the Work listing surface: the real Spring Security filter chain
 * + WorkController + WorkServiceImpl + WorkMapper, over MockMvc, against real Postgres. GET is
 * public (per SecurityConfig's {@code /api/v1/**} rule). Confirms visibility filtering (only
 * PUBLIC works are ever listed), the {@code ?tagId=} filter, the 404 on an unknown tag id, and
 * that the response's nested tags are the real MapStruct-wired TagDto (not a stub).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkControllerTest {

  @Autowired MockMvc mockMvc;
  @Autowired WorkRepository workRepository;
  @Autowired TagRepository tagRepository;
  @Autowired ContributionRepository contributionRepository;
  @Autowired UserRepository userRepository;
  @Autowired ObjectMapper objectMapper;

  private User author;

  @BeforeEach
  void clean() {
    contributionRepository.deleteAll();
    workRepository.deleteAll();
    tagRepository.deleteAll();
    userRepository.deleteAll();
    author = null;
  }

  private User author() {
    if (author == null) {
      author = userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@b.com")
          .username("u" + UUID.randomUUID().toString().substring(0, 8))
          .password("hashed")
          .role(Role.USER)
          .build());
    }
    return author;
  }

  private Work persistWork(String title, Visibility visibility, Tag... tags) {
    return workRepository.save(Work.builder()
        .title(title)
        .preface("preface")
        .workType(WorkType.NOVEL)
        .turnStyle(TurnStyle.ROBINROUND)
        .writingMode(WritingMode.DEFAULT)
        .visibility(visibility)
        .createdAuthor(author())
        .tags(Set.of(tags))
        .build());
  }

  // --- GET /api/v1/works -----------------------------------------------------

  @Test
  void getAllWorks_isPubliclyAccessible_evenWithoutLogin() throws Exception {
    persistWork("Public Story", Visibility.PUBLIC);

    mockMvc.perform(get("/api/v1/works"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("Public Story"));
  }

  @Test
  void getAllWorks_excludesUnlistedWorks() throws Exception {
    persistWork("Public Story", Visibility.PUBLIC);
    persistWork("Secret Story", Visibility.UNLISTED);

    mockMvc.perform(get("/api/v1/works"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].title").value("Public Story"));
  }

  @Test
  void getAllWorks_returnsEmptyList_whenNoPublicWorks() throws Exception {
    persistWork("Secret Story", Visibility.UNLISTED);

    mockMvc.perform(get("/api/v1/works"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void getAllWorks_includesRequiresAccessCodeAndTags() throws Exception {
    Tag tag = tagRepository.save(Tag.builder().name("fantasy").build());
    Work work = persistWork("Locked Story", Visibility.PUBLIC, tag);
    work.setAccessCode("secret-code");
    workRepository.save(work);

    mockMvc.perform(get("/api/v1/works"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].requiresAccessCode").value(true))
        .andExpect(jsonPath("$[0].tags[0].name").value("fantasy"));
  }

  @Test
  void getAllWorks_requiresAccessCodeIsFalse_whenNoAccessCode() throws Exception {
    persistWork("Open Story", Visibility.PUBLIC);

    mockMvc.perform(get("/api/v1/works"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].requiresAccessCode").value(false));
  }

  // --- GET /api/v1/works?tagId= ----------------------------------------------

  @Test
  void getAllWorks_filteredByTagId_returnsOnlyMatchingWorks() throws Exception {
    Tag fantasy = tagRepository.save(Tag.builder().name("fantasy").build());
    Tag horror = tagRepository.save(Tag.builder().name("horror").build());
    persistWork("Fantasy Story", Visibility.PUBLIC, fantasy);
    persistWork("Horror Story", Visibility.PUBLIC, horror);

    mockMvc.perform(get("/api/v1/works").param("tagId", fantasy.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
        .andExpect(jsonPath("$[0].title").value("Fantasy Story"));
  }

  @Test
  void getAllWorks_filteredByTagId_excludesUnlistedWorks_evenWithMatchingTag() throws Exception {
    Tag fantasy = tagRepository.save(Tag.builder().name("fantasy").build());
    persistWork("Hidden Fantasy", Visibility.UNLISTED, fantasy);

    mockMvc.perform(get("/api/v1/works").param("tagId", fantasy.getId().toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isEmpty());
  }

  @Test
  void getAllWorks_unknownTagId_returns404() throws Exception {
    mockMvc.perform(get("/api/v1/works").param("tagId", UUID.randomUUID().toString()))
        .andExpect(status().isNotFound());
  }

  // --- POST /api/v1/works -----------------------------------------------------

  private UserDetail persistLoggedInUser() {
    User user = userRepository.save(User.builder()
        .email(UUID.randomUUID() + "@b.com")
        .username("u" + UUID.randomUUID().toString().substring(0, 8))
        .password("hashed")
        .role(Role.USER)
        .build());
    return new UserDetail(user);
  }

  private String createWorkBody(java.util.Map<String, Object> overrides) throws Exception {
    java.util.Map<String, Object> body = new java.util.HashMap<>();
    body.put("title", "The Long Story");
    body.put("preface", "Once upon a time");
    body.put("workType", "NOVEL");
    body.put("turnStyle", "ROBINROUND");
    body.put("writingMode", "DEFAULT");
    body.putAll(overrides);
    return objectMapper.writeValueAsString(body);
  }

  @Test
  void createWork_withoutLogin_returns401() throws Exception {
    mockMvc.perform(post("/api/v1/works")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWorkBody(java.util.Map.of())))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void createWork_withoutCsrfToken_returns403() throws Exception {
    mockMvc.perform(post("/api/v1/works")
            .with(user(persistLoggedInUser()))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWorkBody(java.util.Map.of())))
        .andExpect(status().isForbidden());
  }

  @Test
  void createWork_missingRequiredField_returns400() throws Exception {
    java.util.Map<String, Object> overrides = new java.util.HashMap<>();
    overrides.put("workType", null); // required by @NotNull
    mockMvc.perform(post("/api/v1/works")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWorkBody(overrides)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void createWork_validBody_returns201_andPersistsWithCreatedAuthorAndDefaults() throws Exception {
    UserDetail me = persistLoggedInUser();

    mockMvc.perform(post("/api/v1/works")
            .with(user(me)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWorkBody(java.util.Map.of())))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.title").value("The Long Story"))
        .andExpect(jsonPath("$.visibility").value("PUBLIC"))
        .andExpect(jsonPath("$.maxWords").value(1000))
        .andExpect(jsonPath("$.maxParagraphs").value(1))
        .andExpect(jsonPath("$.requiresAccessCode").value(false))
        .andExpect(jsonPath("$.createdAuthor.id").value(me.getId().toString()))
        .andExpect(jsonPath("$.createdAuthor.username").value(me.getUsername()));

    assertThat(workRepository.findAll()).hasSize(1);
    assertThat(workRepository.findAll().get(0).getCreatedAuthor().getId()).isEqualTo(me.getId());
  }

  @Test
  void createWork_withTagNames_reusesExistingTag_doesNotDuplicate() throws Exception {
    tagRepository.save(Tag.builder().name("fantasy").build());
    java.util.Map<String, Object> overrides = new java.util.HashMap<>();
    overrides.put("tagNames", Set.of("fantasy"));

    mockMvc.perform(post("/api/v1/works")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWorkBody(overrides)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.tags[0].name").value("fantasy"));

    assertThat(tagRepository.findAll()).hasSize(1);
  }

  @Test
  void createWork_withAccessCode_setsRequiresAccessCodeTrue() throws Exception {
    java.util.Map<String, Object> overrides = new java.util.HashMap<>();
    overrides.put("accessCode", "letmein");

    mockMvc.perform(post("/api/v1/works")
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(createWorkBody(overrides)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.requiresAccessCode").value(true));
  }

  // --- GET /api/v1/works/{id} --------------------------------------------------

  @Test
  void getWorkById_existingWork_isPubliclyAccessible_evenWithoutLogin() throws Exception {
    Work work = persistWork("Public Story", Visibility.PUBLIC);

    mockMvc.perform(get("/api/v1/works/" + work.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(work.getId().toString()))
        .andExpect(jsonPath("$.title").value("Public Story"));
  }

  @Test
  void getWorkById_includesTagsAndCreatedAuthor() throws Exception {
    Tag tag = tagRepository.save(Tag.builder().name("fantasy").build());
    Work work = persistWork("Tagged Story", Visibility.PUBLIC, tag);

    mockMvc.perform(get("/api/v1/works/" + work.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tags[0].name").value("fantasy"))
        .andExpect(jsonPath("$.createdAuthor.username").value(author().getUsername()));
  }

  @Test
  void getWorkById_unknownId_returns404() throws Exception {
    mockMvc.perform(get("/api/v1/works/" + UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }

  @Test
  void getWorkById_unlistedWork_isStillReadableDirectly() throws Exception {
    // Direct-by-id read is not gated by visibility; only the /works listing filters it out.
    Work work = persistWork("Secret Story", Visibility.UNLISTED);

    mockMvc.perform(get("/api/v1/works/" + work.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Secret Story"));
  }

  // --- PATCH /api/v1/works/{id} ------------------------------------------------

  @Test
  void updateWork_withoutLogin_returns401() throws Exception {
    Work work = persistWork("A Story", Visibility.PUBLIC);

    mockMvc.perform(patch("/api/v1/works/" + work.getId())
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"completed\":true}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void updateWork_withoutCsrfToken_returns403() throws Exception {
    Work work = persistWork("A Story", Visibility.PUBLIC);

    mockMvc.perform(patch("/api/v1/works/" + work.getId())
            .with(user(persistLoggedInUser()))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"completed\":true}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void updateWork_setsCompleted_returnsUpdatedDetail() throws Exception {
    Work work = persistWork("A Story", Visibility.PUBLIC);

    mockMvc.perform(patch("/api/v1/works/" + work.getId())
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"completed\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.completed").value(true));

    assertThat(workRepository.findById(work.getId()).orElseThrow().isCompleted()).isTrue();
  }

  @Test
  void updateWork_setsAccessCode_returnsRequiresAccessCodeTrue() throws Exception {
    Work work = persistWork("A Story", Visibility.PUBLIC);

    mockMvc.perform(patch("/api/v1/works/" + work.getId())
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accessCode\":\"letmein\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.requiresAccessCode").value(true));

    assertThat(workRepository.findById(work.getId()).orElseThrow().getAccessCode())
        .isEqualTo("letmein");
  }

  @Test
  void updateWork_emptyAccessCode_returns400_doesNotClearExistingCode() throws Exception {
    Work work = persistWork("A Story", Visibility.PUBLIC);
    work.setAccessCode("original");
    workRepository.save(work);

    mockMvc.perform(patch("/api/v1/works/" + work.getId())
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"accessCode\":\"\"}"))
        .andExpect(status().isBadRequest());

    assertThat(workRepository.findById(work.getId()).orElseThrow().getAccessCode())
        .isEqualTo("original");
  }

  @Test
  void updateWork_ignoresFieldsOutsideContract() throws Exception {
    Work work = persistWork("Original Title", Visibility.PUBLIC);

    mockMvc.perform(patch("/api/v1/works/" + work.getId())
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"Hacked Title\"}"))
        .andExpect(status().isOk());

    assertThat(workRepository.findById(work.getId()).orElseThrow().getTitle())
        .isEqualTo("Original Title");
  }

  @Test
  void updateWork_unknownId_returns404() throws Exception {
    mockMvc.perform(patch("/api/v1/works/" + UUID.randomUUID())
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"completed\":true}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void updateWork_alreadyCompleted_cannotBeSetBackToFalse_returns409() throws Exception {
    Work work = persistWork("A Story", Visibility.PUBLIC);
    work.setCompleted(true);
    workRepository.save(work);

    mockMvc.perform(patch("/api/v1/works/" + work.getId())
            .with(user(persistLoggedInUser())).with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"completed\":false}"))
        .andExpect(status().isConflict());

    assertThat(workRepository.findById(work.getId()).orElseThrow().isCompleted()).isTrue();
  }
}
