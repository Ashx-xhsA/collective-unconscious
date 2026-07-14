package com.cu.collective_unconscious.mappers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.dtos.UserDto;

/**
 * Unit test for the MapStruct-generated {@link UserMapper}. No Spring context — we exercise the
 * generated implementation directly. The security-critical assertion here is that the password
 * hash NEVER crosses into the outward-facing DTO.
 */
class UserMapperTest {

  private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

  @Test
  void toDto_copiesAllPublicFields_andMapsRoleEnumToName() {
    UUID id = UUID.randomUUID();
    User user = User.builder()
        .id(id)
        .email("a@b.com")
        .username("ash")
        .displayName("Ash")
        .password("$2a$10$secret-hash")
        .role(Role.ADMIN)
        .build();

    UserDto dto = mapper.toDto(user);

    assertThat(dto.getId()).isEqualTo(id);
    assertThat(dto.getUsername()).isEqualTo("ash");
    assertThat(dto.getEmail()).isEqualTo("a@b.com");
    assertThat(dto.getDisplayName()).isEqualTo("Ash");
    assertThat(dto.getRole()).isEqualTo("ADMIN"); // enum -> name()
  }

  @Test
  void userDto_hasNoPasswordField_soTheHashCanNeverLeak() {
    // Type-level guarantee: there is simply no field on the outward DTO to carry a password.
    assertThat(Arrays.stream(UserDto.class.getDeclaredFields()).map(Field::getName))
        .doesNotContain("password");
  }

  @Test
  void toDto_returnsNull_forNullInput() {
    assertThat(mapper.toDto(null)).isNull();
  }

  @Test
  void toDto_toleratesNullDisplayName() {
    User user = User.builder()
        .id(UUID.randomUUID())
        .email("a@b.com")
        .username("ash")
        .password("x")
        .role(Role.USER)
        .build();

    assertThat(mapper.toDto(user).getDisplayName()).isNull();
  }
}
