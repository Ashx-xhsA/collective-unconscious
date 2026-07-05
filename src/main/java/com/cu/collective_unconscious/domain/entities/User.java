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

  @Column(nullable = true, unique = true)
  private String email;

  @Column(nullable = false)
  private String password;

  @Column(nullable = false,unique = true)
  private String name;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  @Builder.Default
  private Role role = Role.USER;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  // Identity-based equals/hashCode: hashing mutable fields breaks Set membership
  // once an entity is persisted/updated (User lives inside Work.authors as a Set).
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof User user)) return false;
    return id != null && id.equals(user.id);
  }

  @Override
  public int hashCode() {
    return getClass().hashCode();
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }
}
