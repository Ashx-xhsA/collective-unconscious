package com.cu.collective_unconscious.dtos;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public-facing author info for read endpoints. Deliberately excludes email/role/password —
 * unlike {@link UserDto}, this is broadcast to anyone reading a work or contribution.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class AuthorDto {
  private UUID id;
  private String username;
  private String displayName;
}
