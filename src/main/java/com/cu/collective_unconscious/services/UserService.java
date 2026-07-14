package com.cu.collective_unconscious.services;

import java.util.UUID;

import com.cu.collective_unconscious.domain.entities.User;

public interface UserService {
  User register(String email, String username, String password, String displayName);

  User getById(UUID id);
}
