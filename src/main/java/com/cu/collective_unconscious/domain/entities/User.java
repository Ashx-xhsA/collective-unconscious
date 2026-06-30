package com.cu.collective_unconscious.domain.entities;


import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
  private LocalDateTime createdAt;

  // We need to implement equals and hashCode explicitly because
  // Lombok's implementation can cause issues with JPA entities
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    User user = (User) o;
    return Objects.equals(id, user.id) &&
            Objects.equals(email, user.email) &&
            Objects.equals(password, user.password) &&
            Objects.equals(name, user.name) &&
            Objects.equals(createdAt, user.createdAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, email, password, name, createdAt);
  }

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }
}
