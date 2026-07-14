package com.cu.collective_unconscious.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.test.context.ActiveProfiles;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.enums.Role;

/**
 * Slice test for {@link UserRepository} against the real Postgres schema (same DB as CI), so we
 * exercise the actual dialect and the unique / not-null constraints — not an H2 approximation.
 * {@code replace = NONE} keeps the configured Postgres datasource instead of an embedded one.
 * Each test runs in a transaction that rolls back, so tests stay isolated.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class UserRepositoryTest {

  @Autowired UserRepository userRepository;

  private User newUser(String email, String username) {
    return User.builder()
        .email(email)
        .username(username)
        .password("hashed")
        .role(Role.USER)
        .build();
  }

  // --- lookups -------------------------------------------------------------

  @Test
  void findByEmail_returnsUser_whenPresent_elseEmpty() {
    userRepository.save(newUser("a@b.com", "ash"));

    assertThat(userRepository.findByEmail("a@b.com")).isPresent();
    assertThat(userRepository.findByEmail("nope@b.com")).isEmpty();
  }

  @Test
  void findByUsername_returnsUser_whenPresent_elseEmpty() {
    userRepository.save(newUser("a@b.com", "ash"));

    assertThat(userRepository.findByUsername("ash")).isPresent();
    assertThat(userRepository.findByUsername("ghost")).isEmpty();
  }

  @Test
  void findByEmailOrUsername_matchesByEmail() {
    userRepository.save(newUser("a@b.com", "ash"));

    // Login passes the same value as both arguments.
    assertThat(userRepository.findByEmailOrUsername("a@b.com", "a@b.com")).isPresent();
  }

  @Test
  void findByEmailOrUsername_matchesByUsername() {
    userRepository.save(newUser("a@b.com", "ash"));

    assertThat(userRepository.findByEmailOrUsername("ash", "ash")).isPresent();
  }

  @Test
  void findByEmailOrUsername_isEmpty_whenNeitherMatches() {
    userRepository.save(newUser("a@b.com", "ash"));

    assertThat(userRepository.findByEmailOrUsername("x", "x")).isEmpty();
  }

  @Test
  void existsByEmail_isTrueOnlyForSavedEmail() {
    userRepository.save(newUser("a@b.com", "ash"));

    assertThat(userRepository.existsByEmail("a@b.com")).isTrue();
    assertThat(userRepository.existsByEmail("nope@b.com")).isFalse();
  }

  @Test
  void existsByUsername_isTrueOnlyForSavedUsername() {
    userRepository.save(newUser("a@b.com", "ash"));

    assertThat(userRepository.existsByUsername("ash")).isTrue();
    assertThat(userRepository.existsByUsername("ghost")).isFalse();
  }

  // --- edge cases ----------------------------------------------------------

  @Test
  void findByEmail_isCaseSensitive() {
    // Locks in current behaviour: Postgres text comparison is case-sensitive, so a differently
    // cased email does NOT match. If we ever want case-insensitive login, this test must change.
    userRepository.save(newUser("a@b.com", "ash"));

    assertThat(userRepository.findByEmail("A@B.com")).isEmpty();
  }

  @Test
  void save_populatesCreatedAt_viaPrePersist() {
    User saved = userRepository.save(newUser("a@b.com", "ash"));

    assertThat(saved.getCreatedAt()).isNotNull();
  }

  @Test
  void save_duplicateEmail_violatesUniqueConstraint() {
    userRepository.saveAndFlush(newUser("a@b.com", "ash"));

    assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("a@b.com", "other")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void save_duplicateUsername_violatesUniqueConstraint() {
    userRepository.saveAndFlush(newUser("a@b.com", "ash"));

    assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("other@b.com", "ash")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void save_nullEmail_violatesNotNullConstraint() {
    assertThatThrownBy(() -> userRepository.saveAndFlush(newUser(null, "ash")))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void save_nullUsername_violatesNotNullConstraint() {
    assertThatThrownBy(() -> userRepository.saveAndFlush(newUser("a@b.com", null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void save_nullPassword_violatesNotNullConstraint() {
    User u = User.builder().email("a@b.com").username("ash").password(null).role(Role.USER).build();

    assertThatThrownBy(() -> userRepository.saveAndFlush(u))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void findByEmailOrUsername_throws_whenTwoDifferentRowsMatch() {
    // Documented latent edge: one row's email equals another row's username. The single-result
    // query then matches two rows and blows up. In practice RegisterRequest's @Pattern forbids
    // '@' in usernames, so a username can never equal an email — but the DB does not enforce that.
    userRepository.saveAndFlush(newUser("shared@b.com", "ash"));
    userRepository.saveAndFlush(newUser("other@b.com", "shared@b.com"));

    assertThatThrownBy(() -> userRepository.findByEmailOrUsername("shared@b.com", "shared@b.com"))
        .isInstanceOf(IncorrectResultSizeDataAccessException.class);
  }
}
