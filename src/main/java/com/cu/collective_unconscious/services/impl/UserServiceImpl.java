package com.cu.collective_unconscious.services.impl;

import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.domain.entities.enums.Role;
import com.cu.collective_unconscious.repository.UserRepository;
import com.cu.collective_unconscious.services.UserService;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;

  @Override
  public User register(String email, String username, String password, String displayName) {
    if (email == null || email.isBlank()
        || username == null || username.isBlank()
        || password == null || password.isBlank()) {
      // -> 400 via ErrorController#handleIllegalArgumentException
      throw new IllegalArgumentException("Email, username and password are required");
    }

    // Check uniqueness up front so we return a clean 409 instead of a raw DB
    // constraint violation (both columns are unique = true on the User entity).
    if (userRepository.existsByEmail(email)) {
      // -> 409 via ErrorController#handleIllegalStateException
      throw new IllegalStateException("Email already in use: " + email);
    }
    if (userRepository.existsByUsername(username)) {
      throw new IllegalStateException("Username already taken: " + username);
    }

    User user = User.builder()
        .email(email)
        .username(username)
        .password(passwordEncoder.encode(password)) // never store the raw password
        .displayName(displayName)
        .role(Role.USER)
        .build();

    return userRepository.save(user);
  }

  @Override
  public User getById(UUID id) {
    return userRepository.findById(id)
        .orElseThrow(() -> new EntityNotFoundException("User not found: " + id));
  }
}
