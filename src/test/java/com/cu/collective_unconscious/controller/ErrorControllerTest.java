package com.cu.collective_unconscious.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Verifies the {@link ErrorController} @ControllerAdvice maps each exception type to the intended
 * HTTP status. Runs in the full application context (where the advice is provably active) with a
 * tiny throwing controller as the exception source; security filters are disabled
 * ({@code addFilters = false}) so requests reach the handlers without auth/CSRF noise.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class ErrorControllerTest {

  @Autowired MockMvc mockMvc;

  @Test
  void illegalArgument_mapsTo400() throws Exception {
    mockMvc.perform(get("/t/iae"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("bad arg"));
  }

  @Test
  void illegalState_mapsTo409() throws Exception {
    mockMvc.perform(get("/t/ise"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value("conflict"));
  }

  @Test
  void badCredentials_mapsTo401_withGenericMessage() throws Exception {
    // Message must NOT reveal whether the user exists.
    mockMvc.perform(get("/t/bad-credentials"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Incorrect username or password"));
  }

  @Test
  void entityNotFound_mapsTo404() throws Exception {
    mockMvc.perform(get("/t/not-found"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unhandledException_mapsTo500_withoutLeakingDetails() throws Exception {
    mockMvc.perform(get("/t/boom"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"));
  }

  @Test
  void validationFailure_mapsTo400_withPerFieldErrors() throws Exception {
    mockMvc.perform(post("/t/validate")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Validation failed"))
        .andExpect(jsonPath("$.errors[0].field").value("name"));
  }

  @Test
  void unmappedPath_mapsTo404_notInternalServerError() throws Exception {
    // No controller anywhere maps this path. Spring's own DispatcherServlet fallback raises
    // NoResourceFoundException (a 404-classified ErrorResponseException) for it — that must not
    // get swallowed by the generic Exception.class -> 500 handler above.
    mockMvc.perform(get("/api/v1/this-route-does-not-exist"))
        .andExpect(status().isNotFound());
  }

  @Test
  void unmappedPath_viaPatch_mapsTo404() throws Exception {
    mockMvc.perform(patch("/api/v1/this-route-does-not-exist"))
        .andExpect(status().isNotFound());
  }

  @Test
  void malformedJsonBody_mapsTo400() throws Exception {
    // A broken request body raises HttpMessageNotReadableException, which ErrorController maps to
    // 400 (client error) rather than letting it fall through to the generic 500 handler.
    mockMvc.perform(post("/t/body")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{ this is not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Malformed request body"));
  }

  /** Registers the test-only throwing controller into the context. */
  @TestConfiguration
  static class ThrowingControllerConfig {
    @Bean
    ThrowingController throwingController() {
      return new ThrowingController();
    }
  }

  /** Test-only controller whose sole purpose is to throw, so the advice has something to catch. */
  @RestController
  static class ThrowingController {

    @GetMapping("/t/iae")
    void iae() {
      throw new IllegalArgumentException("bad arg");
    }

    @GetMapping("/t/ise")
    void ise() {
      throw new IllegalStateException("conflict");
    }

    @GetMapping("/t/bad-credentials")
    void badCredentials() {
      throw new BadCredentialsException("internal detail that must not leak");
    }

    @GetMapping("/t/not-found")
    void notFound() {
      throw new EntityNotFoundException("missing");
    }

    @GetMapping("/t/boom")
    void boom() {
      throw new RuntimeException("boom");
    }

    @PostMapping("/t/validate")
    void validate(@Valid @RequestBody Body body) {
      // reaching here means validation passed
    }

    @PostMapping("/t/body")
    void body(@RequestBody Body body) {
      // never reached with malformed JSON
    }

    record Body(@NotBlank String name) {}
  }
}
