package com.cu.collective_unconscious.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.enums.Role;

/**
 * Locks in the P0 fix: the session principal must survive Java serialization, because Spring
 * Session serializes it into Redis. If someone later makes UserDetail hold the User entity (which
 * will gain lazy collections), this test fails loudly in CI instead of at runtime on first login.
 */
class UserDetailSerializationTest {

  @Test
  void principal_survivesJavaSerialization() throws Exception {
    User user = User.builder()
        .id(UUID.randomUUID())
        .email("a@b.com")
        .username("ash")
        .password("$2a$hashed")
        .role(Role.ADMIN)
        .build();
    UserDetail original = new UserDetail(user);

    // serialize -> deserialize, simulating a Redis session round-trip
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
      out.writeObject(original);
    }
    Object restored;
    try (ObjectInputStream in =
        new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
      restored = in.readObject();
    }

    assertThat(restored).isInstanceOf(UserDetail.class);
    UserDetail result = (UserDetail) restored;
    assertThat(result.getId()).isEqualTo(user.getId());
    assertThat(result.getUsername()).isEqualTo("ash");
    assertThat(result.getPassword()).isEqualTo("$2a$hashed");
    assertThat(result.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .contains("ROLE_ADMIN");
  }
}
