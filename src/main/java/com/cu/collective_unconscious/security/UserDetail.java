package com.cu.collective_unconscious.security;

import com.cu.collective_unconscious.domain.entities.User;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import lombok.Getter;

/**
 * Spring Security principal stored in the (Redis-backed) session.
 *
 * <p>Holds only flat, auth-relevant fields copied from {@link User} — deliberately NOT the
 * entity itself. Once {@code User} gains lazy M2M/OTM collections (Work.authors, tickets,
 * follows, ...), serializing the whole entity into every session would drag the object graph
 * into Redis or blow up on lazy init. This principal stays small and Serializable-safe.
 *
 * <p>For mutable / relational data, re-query the live row via {@link #getId()} — the principal
 * answers "who is this + what role", the database answers "what do they have right now".
 */
@Getter
public class UserDetail implements UserDetails, Serializable {

  private static final long serialVersionUID = 1L;

  private final UUID id;
  private final String username;
  private final String password; // the hash; required by getPassword() during login
  private final String role;     // e.g. "ADMIN" / "USER"

  public UserDetail(User user) {
    this.id = user.getId();
    this.username = user.getUsername();
    this.password = user.getPassword();
    this.role = user.getRole().name();
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public boolean isAccountNonExpired() {
    return true;
  }

  @Override
  public boolean isAccountNonLocked() {
    return true;
  }

  @Override
  public boolean isCredentialsNonExpired() {
    return true;
  }

  @Override
  public boolean isEnabled() {
    return true;
  }
}
