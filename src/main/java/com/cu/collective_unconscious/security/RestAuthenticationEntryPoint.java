package com.cu.collective_unconscious.security;

import com.cu.collective_unconscious.dtos.ApiErrorResponse;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * Returns a JSON body for unauthenticated requests instead of an empty 401.
 *
 * <p>An unauthenticated request is rejected by Spring Security in the filter chain, BEFORE it
 * reaches any controller — so the {@code @ControllerAdvice} error handler never sees it. Without
 * this entry point the response is 401 with an empty body, which browsers render as their own
 * "This page isn't working" page. Here we write the same {@link ApiErrorResponse} shape used
 * everywhere else so API clients get a consistent, readable error.
 */
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

  private final ObjectMapper objectMapper;

  @Override
  public void commence(HttpServletRequest request, HttpServletResponse response,
                       AuthenticationException authException) throws IOException {
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);

    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.UNAUTHORIZED.value())
            .message("You are not authenticated. Please log in first.")
            .build();

    objectMapper.writeValue(response.getWriter(), error);
  }
}
