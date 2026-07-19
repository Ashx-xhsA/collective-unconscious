package com.cu.collective_unconscious;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.cu.collective_unconscious.repository.ContributionRepository;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.repository.WorkRepository;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end integration test of the auth surface: the real Spring Security filter chain +
 * AuthController + error mapping, over MockMvc, against real Postgres. Redis session auto-config
 * is excluded in the test profile, so an in-memory servlet HttpSession is used — no Redis needed.
 *
 * <p>CSRF is enabled in {@code SecurityConfig}, so every state-changing POST carries {@code
 * .with(csrf())}. GET requests are exempt. The login → session → me path threads the real
 * HttpSession created during login, exercising the actual SecurityContext persistence.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthFlowIntegrationTest {

  @Autowired MockMvc mockMvc;
  @Autowired UserRepository userRepository;
  @Autowired WorkRepository workRepository;
  @Autowired ContributionRepository contributionRepository;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    // FK order: contributions -> works (clears work_authors/work_tags) -> users. Maven Surefire's
    // default runOrder doesn't guarantee this class runs before Work/ContributionFlowIntegrationTest,
    // so leftover rows from those suites must not break a plain userRepository.deleteAll() here.
    contributionRepository.deleteAll();
    workRepository.deleteAll();
    userRepository.deleteAll();
  }

  // --- helpers -------------------------------------------------------------

  private String json(Map<String, String> body) throws Exception {
    return objectMapper.writeValueAsString(body);
  }

  private Map<String, String> registerBody(String email, String username, String password) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("email", email);
    m.put("username", username);
    m.put("password", password);
    return m;
  }

  private void register(String email, String username, String password) throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(registerBody(email, username, password))))
        .andExpect(status().isCreated());
  }

  private MvcResult login(String emailOrUsername, String password) throws Exception {
    return mockMvc.perform(post("/api/auth/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("emailOrUsername", emailOrUsername, "password", password))))
        .andReturn();
  }

  // --- register ------------------------------------------------------------

  @Test
  void register_validPayload_returns201_withUserDto_andNoPassword() throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(registerBody("a@b.com", "ash", "password1"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").exists())
        .andExpect(jsonPath("$.username").value("ash"))
        .andExpect(jsonPath("$.email").value("a@b.com"))
        .andExpect(jsonPath("$.role").value("USER"))
        .andExpect(jsonPath("$.password").doesNotExist());
  }

  @Test
  void register_persistsBcryptHash_notPlaintext() throws Exception {
    register("a@b.com", "ash", "password1");

    String stored = userRepository.findByEmail("a@b.com").orElseThrow().getPassword();
    assertThat(stored).isNotEqualTo("password1");
    // The DelegatingPasswordEncoder prefixes the algorithm id, e.g. {bcrypt}$2a$10$...
    assertThat(stored).startsWith("{bcrypt}$2");
  }

  @Test
  void register_isPubliclyAccessible() throws Exception {
    // A public endpoint must not answer 401.
    register("a@b.com", "ash", "password1");
  }

  @Test
  void register_withoutDisplayName_succeeds() throws Exception {
    register("a@b.com", "ash", "password1"); // helper omits displayName
  }

  @Test
  void register_duplicateEmail_returns409() throws Exception {
    register("a@b.com", "ash", "password1");

    mockMvc.perform(post("/api/auth/register")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(registerBody("a@b.com", "different", "password1"))))
        .andExpect(status().isConflict());
  }

  @Test
  void register_duplicateUsername_returns409() throws Exception {
    register("a@b.com", "ash", "password1");

    mockMvc.perform(post("/api/auth/register")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(registerBody("other@b.com", "ash", "password1"))))
        .andExpect(status().isConflict());
  }

  @Test
  void register_invalidEmailFormat_returns400() throws Exception {
    expectRegisterBadRequest(registerBody("notanemail", "ash", "password1"));
  }

  @Test
  void register_missingEmail_returns400() throws Exception {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("username", "ash");
    body.put("password", "password1");
    expectRegisterBadRequest(body);
  }

  @Test
  void register_blankUsername_returns400() throws Exception {
    expectRegisterBadRequest(registerBody("a@b.com", "", "password1"));
  }

  @Test
  void register_usernameTooShort_returns400() throws Exception {
    expectRegisterBadRequest(registerBody("a@b.com", "ab", "password1"));
  }

  @Test
  void register_usernameTooLong_returns400() throws Exception {
    expectRegisterBadRequest(registerBody("a@b.com", "a".repeat(31), "password1"));
  }

  @Test
  void register_usernameContainingAt_returns400() throws Exception {
    expectRegisterBadRequest(registerBody("a@b.com", "a@b", "password1"));
  }

  @Test
  void register_passwordTooShort_returns400() throws Exception {
    expectRegisterBadRequest(registerBody("a@b.com", "ash", "short"));
  }

  @Test
  void register_missingPassword_returns400() throws Exception {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("email", "a@b.com");
    body.put("username", "ash");
    expectRegisterBadRequest(body);
  }

  @Test
  void register_malformedJson_returns400() throws Exception {
    // A broken request body is a client error (400), handled by ErrorController.
    mockMvc.perform(post("/api/auth/register")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ broken json"))
        .andExpect(status().isBadRequest());
  }

  private void expectRegisterBadRequest(Map<String, String> body) throws Exception {
    mockMvc.perform(post("/api/auth/register")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(body)))
        .andExpect(status().isBadRequest());
  }

  // --- login ---------------------------------------------------------------

  @Test
  void login_withEmail_returns200_withUserDto_andCreatesSession() throws Exception {
    register("a@b.com", "ash", "password1");

    MvcResult result = mockMvc.perform(post("/api/auth/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("emailOrUsername", "a@b.com", "password", "password1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("ash"))
        .andExpect(jsonPath("$.email").value("a@b.com"))
        .andReturn();

    // A successful login must establish a server-side session (which carries the SESSION cookie
    // in the real app). MockMvc does not emit the container's session cookie, so we assert the
    // session itself was created.
    assertThat(result.getRequest().getSession(false)).isNotNull();
  }

  @Test
  void login_withUsername_returns200() throws Exception {
    register("a@b.com", "ash", "password1");

    mockMvc.perform(post("/api/auth/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("emailOrUsername", "ash", "password", "password1"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("ash"));
  }

  @Test
  void login_wrongPassword_returns401() throws Exception {
    register("a@b.com", "ash", "password1");

    mockMvc.perform(post("/api/auth/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("emailOrUsername", "a@b.com", "password", "WRONG"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void login_unknownUser_returns401() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("emailOrUsername", "ghost@b.com", "password", "password1"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void login_blankCredentials_returns401() throws Exception {
    mockMvc.perform(post("/api/auth/login")
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("emailOrUsername", "", "password", ""))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void login_withoutCsrfToken_returns403() throws Exception {
    // Regression guard: CSRF protection must reject a state-changing POST that carries no token.
    register("a@b.com", "ash", "password1");

    mockMvc.perform(post("/api/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json(Map.of("emailOrUsername", "a@b.com", "password", "password1"))))
        .andExpect(status().isForbidden());
  }

  // --- /me + session lifecycle --------------------------------------------

  @Test
  void me_withoutLogin_returns401() throws Exception {
    mockMvc.perform(get("/api/auth/me"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void me_afterLogin_returnsCurrentUser() throws Exception {
    register("a@b.com", "ash", "password1");
    MockHttpSession session = (MockHttpSession) login("a@b.com", "password1")
        .getRequest().getSession(false);


    mockMvc.perform(get("/api/auth/me").session(session))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value("ash"))
        .andExpect(jsonPath("$.email").value("a@b.com"));
  }

  @Test
  void logout_returns200_andInvalidatesSession() throws Exception {
    register("a@b.com", "ash", "password1");
    MockHttpSession session = (MockHttpSession) login("a@b.com", "password1")
        .getRequest().getSession(false);

    mockMvc.perform(post("/api/auth/logout").with(csrf()).session(session))
        .andExpect(status().isOk());

    // The session backing the login is now invalid — the login no longer authenticates anything.
    assertThat(session.isInvalid()).isTrue();
  }

  @Test
  void logout_withoutSession_isIdempotent_returns200() throws Exception {
    mockMvc.perform(post("/api/auth/logout").with(csrf()))
        .andExpect(status().isOk());
  }

  // --- authorization rules -------------------------------------------------

  @Test
  void rootEndpoint_isPublic() throws Exception {
    mockMvc.perform(get("/"))
        .andExpect(status().isOk());
  }

  @Test
  void healthEndpoint_isPublic() throws Exception {
    mockMvc.perform(get("/actuator/health"))
        .andExpect(status().isOk());
  }

  @Test
  void adminRoute_withoutLogin_returns401() throws Exception {
    mockMvc.perform(get("/api/admin/anything"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void adminRoute_asRegularUser_returns403() throws Exception {
    // A USER is authenticated but lacks ROLE_ADMIN -> access denied (403), not 401.
    mockMvc.perform(get("/api/admin/anything").with(user("ash").roles("USER")))
        .andExpect(status().isForbidden());
  }
}
