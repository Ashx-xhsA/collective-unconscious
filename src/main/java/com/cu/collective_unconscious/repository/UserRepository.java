package com.cu.collective_unconscious.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cu.collective_unconscious.domain.entities.User;

public interface UserRepository extends JpaRepository<User, UUID> {
  Optional<User> findByEmail(String email);
  Optional<User> findByUsername(String username);

  Optional<User> findByEmailOrUsername(String email, String username);
  boolean existsByEmail(String email);
  boolean existsByUsername(String username);
}