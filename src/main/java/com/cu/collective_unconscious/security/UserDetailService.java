package com.cu.collective_unconscious.security;


import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserDetailService implements UserDetailsService {

  private final UserRepository userRepository;

  @Override
  public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
    User user = userRepository.findByEmailOrUsername(usernameOrEmail, usernameOrEmail)
        .orElseThrow(() -> new UsernameNotFoundException("User not found: " + usernameOrEmail));
    return new UserDetail(user);
  }
}
