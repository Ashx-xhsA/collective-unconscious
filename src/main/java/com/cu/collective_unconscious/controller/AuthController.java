package com.cu.collective_unconscious.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cu.collective_unconscious.domain.entities.User;
import com.cu.collective_unconscious.dtos.LoginRequest;
import com.cu.collective_unconscious.dtos.RegisterRequest;
import com.cu.collective_unconscious.dtos.UserDto;
import com.cu.collective_unconscious.mappers.UserMapper;
import com.cu.collective_unconscious.security.UserDetail;
import com.cu.collective_unconscious.services.UserService;

import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping(path = "/api/auth")
@RequiredArgsConstructor
public class AuthController {

  private final UserService userService;
  private final UserMapper userMapper;
  private final AuthenticationManager authenticationManager;

  // Persists the authenticated SecurityContext into the (Redis-backed) HttpSession,
  // which is what makes the SESSION cookie carry the login across requests.
  private final SecurityContextRepository securityContextRepository =
      new HttpSessionSecurityContextRepository();

  @PostMapping("/register")
  public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest request) {
    User user = userService.register(
        request.getEmail(),
        request.getUsername(),
        request.getPassword(),
        request.getDisplayName()
    );
    return new ResponseEntity<>(userMapper.toDto(user), HttpStatus.CREATED);
  }

  /** JSON 登录:认证成功后把 SecurityContext 存进 session,并返回当前用户。失败抛
   *  BadCredentialsException -> 401(见 ErrorController)。 */
  @PostMapping("/login")
  public ResponseEntity<UserDto> login(@RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse httpResponse) {
    Authentication authentication = authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(
            request.getEmailOrUsername(), request.getPassword()));

    // Spring Security 6: must explicitly persist the context, or the next request
    // is treated as anonymous.
    SecurityContext context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(authentication);
    SecurityContextHolder.setContext(context);
    securityContextRepository.saveContext(context, httpRequest, httpResponse);

    UserDetail principal = (UserDetail) authentication.getPrincipal();
    return ResponseEntity.ok(userMapper.toDto(userService.getById(principal.getId())));
  }
  /** 返回当前登录用户，未登录则 401。 */
  @GetMapping("/me")
  public ResponseEntity<UserDto> me(@AuthenticationPrincipal UserDetail principal) {
    if (principal == null) {
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }
    User user = userService.getById(principal.getId());
    return ResponseEntity.ok(userMapper.toDto(user));
  }
}
