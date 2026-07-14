package com.cu.collective_unconscious.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.repository.UserRepository;

/**
 * Pure unit test for {@link UserDetailService} — the bridge Spring Security calls during
 * authentication. The repository is mocked, so no database is involved.
 */
@ExtendWith(MockitoExtension.class)
class UserDetailServiceTest {

  @Mock UserRepository userRepository;
  @InjectMocks UserDetailService service;

  private User user(String email, String username, Role role) {
    return User.builder()
        .id(UUID.randomUUID())
        .email(email)
        .username(username)
        .password("$2a$10$hashed")
        .role(role)
        .build();
  }

  @Test
  void loadUserByUsername_queriesByEmailOrUsername_soEitherIdentifierWorks() {
    // The login field is one opaque string; the OR-query is what lets a user sign in
    // with EITHER their email or their username.
    User u = user("a@b.com", "ash", Role.USER);
    when(userRepository.findByEmailOrUsername("ash", "ash")).thenReturn(Optional.of(u));

    UserDetails details = service.loadUserByUsername("ash");

    assertThat(details.getUsername()).isEqualTo("ash");
    verify(userRepository).findByEmailOrUsername("ash", "ash");
  }

  @Test
  void loadUserByUsername_mapsAdminRoleToRoleAdminAuthority() {
    when(userRepository.findByEmailOrUsername("boss@b.com", "boss@b.com"))
        .thenReturn(Optional.of(user("boss@b.com", "boss", Role.ADMIN)));

    UserDetails details = service.loadUserByUsername("boss@b.com");

    assertThat(details.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_ADMIN");
  }

  @Test
  void loadUserByUsername_mapsUserRoleToRoleUserAuthority() {
    when(userRepository.findByEmailOrUsername("a@b.com", "a@b.com"))
        .thenReturn(Optional.of(user("a@b.com", "ash", Role.USER)));

    UserDetails details = service.loadUserByUsername("a@b.com");

    assertThat(details.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_USER");
  }

  @Test
  void loadUserByUsername_throws_whenUserNotFound() {
    when(userRepository.findByEmailOrUsername("ghost", "ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
        .isInstanceOf(UsernameNotFoundException.class)
        .hasMessageContaining("ghost");
  }
}
