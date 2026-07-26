package com.cu.collective_unconscious.domain.entities;


import java.time.LocalDateTime;
import java.util.UUID;

import com.cu.collective_unconscious.domain.entities.enums.Role;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "users")
public class User {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(nullable = false)
  private String password;

  @Column(nullable = false,unique = true)
  private String username;

  @Column(nullable = true,unique = false)
  private String displayName;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private Role role = Role.USER;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  // Identity-based equals/hashCode. Uses getId() (not the raw field) and a fixed class constant so
  // it stays correct when the other object is a Hibernate lazy proxy: a proxy's id FIELD is null
  // and its getClass() is the proxy class, so field-access equals / getClass() hashCode would both
  // fail Set membership (User lives inside Work.authors as a Set).
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof User user)) return false;
    return getId() != null && getId().equals(user.getId());
  }

  @Override
  public int hashCode() {
    return User.class.hashCode();
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }
}
