package com.cu.collective_unconscious.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cu.collective_unconscious.security.RestAuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {
  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                 ObjectMapper objectMapper) throws Exception {
    // Session auth rides on the SESSION cookie, which the browser sends automatically on
    // cross-site requests — so CSRF protection is required. The token is stored in a
    // (non-HttpOnly) XSRF-TOKEN cookie the SPA reads and echoes back in the X-XSRF-TOKEN header.
    //
    // We use the plain CsrfTokenRequestAttributeHandler instead of Spring's default
    // XorCsrfTokenRequestAttributeHandler. The Xor handler adds BREACH hardening by randomising
    // the token on every response, but that only matters when the token is reflected into a
    // compressible response body over HTTPS. On this JSON API the token travels solely via the
    // cookie/header, never the body, so BREACH does not apply. The plain handler lets any
    // standard client (Angular/axios/Postman) read the XSRF-TOKEN cookie and echo it verbatim.
    CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

    http
            .csrf(csrf -> csrf
                    .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                    .csrfTokenRequestHandler(csrfRequestHandler))
            // Force the deferred CSRF token to load on every request so the XSRF-TOKEN cookie is
            // always present — including after a plain GET at app load — not only after a POST.
            .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class)

            .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/auth/register",
                            "/api/auth/login").permitAll()
                    .requestMatchers("/").permitAll()
                    .requestMatchers(HttpMethod.GET,"/api/v1/contributions/drafts").authenticated()
                    // "is it my turn" is caller-relative -> login-only; must precede the blanket
                    // GET permitAll below, or it would fall through as public.
                    .requestMatchers(HttpMethod.GET, "/api/v1/works/*/tickets/current").authenticated()
                    .requestMatchers(HttpMethod.GET, "/api/v1/**").permitAll()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .anyRequest().authenticated()
            )


            // Login is handled by AuthController#login (JSON body) instead of the
            // built-in formLogin, so form-urlencoded parsing is no longer involved.
            .logout(logout -> logout
                    .logoutUrl("/api/auth/logout")
                    .logoutSuccessHandler((req,
                                           res,
                                           authn)
                            -> res.setStatus(HttpStatus.OK.value()))
                    .deleteCookies("SESSION")
                    .invalidateHttpSession(true)
            )

            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(new RestAuthenticationEntryPoint(objectMapper))
            );

    return http.build();
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  /**
   * Reads (and thereby resolves) the deferred CsrfToken on every request, which triggers
   * CookieCsrfTokenRepository to write the XSRF-TOKEN cookie. Without this, the token is loaded
   * lazily and the cookie only appears on requests that actually validate CSRF (e.g. a POST),
   * so a client couldn't read it before its first write request.
   */
  static final class CsrfCookieFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
      CsrfToken csrfToken = (CsrfToken) request.getAttribute("_csrf");
      if (csrfToken != null) {
        csrfToken.getToken();
      }
      filterChain.doFilter(request, response);
    }
  }

}
