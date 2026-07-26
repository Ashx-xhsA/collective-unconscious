package com.cu.collective_unconscious.controller;

import com.cu.collective_unconscious.dtos.ApiErrorResponse;

import java.util.List;

import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestController
@ControllerAdvice
@Slf4j
public class ErrorController {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiErrorResponse> handleException(Exception ex) {
    log.error("Caught exception", ex);
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
            .message("An unexpected error occurred")
            .build();
    return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
  }

  /** Bean-validation failures on @Valid @RequestBody -> 400 with per-field messages. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
    List<ApiErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> ApiErrorResponse.FieldError.builder()
                    .field(fe.getField())
                    .message(fe.getDefaultMessage())
                    .build())
            .toList();
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.BAD_REQUEST.value())
            .message("Validation failed")
            .errors(fieldErrors)
            .build();
    return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
  }

  /** A malformed / unparseable request body is a client error (400), not a server fault. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.BAD_REQUEST.value())
            .message("Malformed request body")
            .build();
    return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiErrorResponse> handleIllegalArgumentException(IllegalArgumentException ex) {
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.BAD_REQUEST.value())
            .message(ex.getMessage())
            .build();
    return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(IllegalStateException.class)
  public ResponseEntity<ApiErrorResponse> handleIllegalStateException(IllegalStateException ex) {
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.CONFLICT.value())
            .message(ex.getMessage())
            .build();
    return new ResponseEntity<>(error, HttpStatus.CONFLICT);
  }

  @ExceptionHandler(BadCredentialsException.class)
  public ResponseEntity<ApiErrorResponse> handleBadCredentialsException(BadCredentialsException ex) {
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.UNAUTHORIZED.value())
            .message("Incorrect username or password")
            .build();
    return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
  }

  /** "You are not allowed to do this" (wrong/missing invitation code, submitting another user's
   * draft) -> 403. Distinct from 409 (state doesn't allow it) and 401 (not authenticated). */
  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(
      org.springframework.security.access.AccessDeniedException ex) {
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.FORBIDDEN.value())
            .message(ex.getMessage())
            .build();
    return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
  }

  @ExceptionHandler(EntityNotFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleEntityNotFoundException(EntityNotFoundException ex) {
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.NOT_FOUND.value())
            .message(ex.getMessage())
            .build();
    return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
  }

  /**
   * Raised by DispatcherServlet's static-resource fallback when a request matches no controller
   * mapping at all (wrong path, typo, or right path with an unsupported HTTP method). Spring
   * already classifies this as a 404 (it's an {@code ErrorResponseException}); without this
   * handler it would fall through to {@link #handleException} above and get misreported as 500.
   */
  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ApiErrorResponse> handleNoResourceFoundException(NoResourceFoundException ex) {
    ApiErrorResponse error = ApiErrorResponse.builder()
            .status(HttpStatus.NOT_FOUND.value())
            .message("No such endpoint")
            .build();
    return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
  }
}