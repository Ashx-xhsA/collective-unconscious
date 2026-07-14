package com.cu.collective_unconscious.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.enums.Role;

/**
 * Unit test for the {@link UserDetail} adapter's field copying and the account-status flags.
 * (Java-serialization round-trip is covered separately by {@link UserDetailSerializationTest}.)
 */
class UserDetailTest {

  private User user(Role role) {
    return User.builder()
        .id(UUID.randomUUID())
        .email("a@b.com")
        .username("ash")
        .password("$2a$10$hash")
        .role(role)
        .build();
  }

  @Test
  void constructor_copiesFlatFieldsFromUser() {
    User u = user(Role.USER);
    UserDetail principal = new UserDetail(u);

    assertThat(principal.getId()).isEqualTo(u.getId());
    assertThat(principal.getUsername()).isEqualTo("ash");
    assertThat(principal.getPassword()).isEqualTo("$2a$10$hash");
  }

  @Test
  void authorities_havePrefixedRole() {
    assertThat(new UserDetail(user(Role.USER)).getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("ROLE_USER");
  }

  @Test
  void accountStatusFlags_areAllActive() {
    UserDetail principal = new UserDetail(user(Role.USER));

    assertThat(principal.isAccountNonExpired()).isTrue();
    assertThat(principal.isAccountNonLocked()).isTrue();
    assertThat(principal.isCredentialsNonExpired()).isTrue();
    assertThat(principal.isEnabled()).isTrue();
  }
}
