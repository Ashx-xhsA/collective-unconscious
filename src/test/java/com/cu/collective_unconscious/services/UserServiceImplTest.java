package com.cu.collective_unconscious.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.services.impl.UserServiceImpl;

/**
 * Pure unit test for {@link UserServiceImpl}. Repository and password encoder are mocked, so this
 * verifies our business rules in isolation: required-field validation, uniqueness checks, password
 * hashing, and the default role — without a database.
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

  @Mock UserRepository userRepository;
  @Mock PasswordEncoder passwordEncoder;
  @InjectMocks UserServiceImpl service;

  // --- register: happy path ------------------------------------------------

  @Test
  void register_savesEncodedPassword_withDefaultUserRole() {
    when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
    when(userRepository.existsByUsername("ash")).thenReturn(false);
    when(passwordEncoder.encode("plaintext")).thenReturn("ENCODED");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    service.register("a@b.com", "ash", "plaintext", "Ash");

    ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("a@b.com");
    assertThat(saved.getValue().getUsername()).isEqualTo("ash");
    assertThat(saved.getValue().getDisplayName()).isEqualTo("Ash");
    assertThat(saved.getValue().getRole()).isEqualTo(Role.USER);
    // security: the raw password must never be persisted
    assertThat(saved.getValue().getPassword()).isEqualTo("ENCODED");
    assertThat(saved.getValue().getPassword()).isNotEqualTo("plaintext");
  }

  @Test
  void register_allowsNullDisplayName() {
    when(userRepository.existsByEmail(anyString())).thenReturn(false);
    when(userRepository.existsByUsername(anyString())).thenReturn(false);
    when(passwordEncoder.encode(anyString())).thenReturn("ENCODED");
    when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

    User result = service.register("a@b.com", "ash", "plaintext", null);

    assertThat(result.getDisplayName()).isNull();
  }

  // --- register: required-field validation (-> 400) ------------------------

  @Test
  void register_rejectsBlankEmail_withoutTouchingRepository() {
    assertThatThrownBy(() -> service.register("  ", "ash", "plaintext", null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(userRepository, never()).save(any());
    verify(userRepository, never()).existsByEmail(anyString());
  }

  @Test
  void register_rejectsNullUsername() {
    assertThatThrownBy(() -> service.register("a@b.com", null, "plaintext", null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(userRepository, never()).save(any());
  }

  @Test
  void register_rejectsBlankPassword() {
    assertThatThrownBy(() -> service.register("a@b.com", "ash", "  ", null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(userRepository, never()).save(any());
  }

  // --- register: uniqueness (-> 409) ---------------------------------------

  @Test
  void register_rejectsDuplicateEmail_withoutSaving() {
    when(userRepository.existsByEmail("a@b.com")).thenReturn(true);

    assertThatThrownBy(() -> service.register("a@b.com", "ash", "plaintext", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Email already in use");
    verify(userRepository, never()).save(any());
  }

  @Test
  void register_rejectsDuplicateUsername_withoutSaving() {
    when(userRepository.existsByEmail("a@b.com")).thenReturn(false);
    when(userRepository.existsByUsername("ash")).thenReturn(true);

    assertThatThrownBy(() -> service.register("a@b.com", "ash", "plaintext", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Username already taken");
    verify(userRepository, never()).save(any());
  }

  @Test
  void register_checksRequiredFieldsBeforeUniqueness() {
    // Blank email must fail validation (400) before any uniqueness query (409) runs.
    assertThatThrownBy(() -> service.register("", "ash", "plaintext", null))
        .isInstanceOf(IllegalArgumentException.class);
    verify(userRepository, never()).existsByEmail(anyString());
    verify(userRepository, never()).existsByUsername(anyString());
  }

  // --- getById -------------------------------------------------------------

  @Test
  void getById_returnsUser_whenPresent() {
    UUID id = UUID.randomUUID();
    User u = User.builder().id(id).email("a@b.com").username("ash").password("x").role(Role.USER).build();
    when(userRepository.findById(id)).thenReturn(Optional.of(u));

    assertThat(service.getById(id)).isSameAs(u);
  }

  @Test
  void getById_throws_whenMissing() {
    UUID id = UUID.randomUUID();
    when(userRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getById(id))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("User not found");
  }
}
