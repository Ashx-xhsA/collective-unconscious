package com.cu.collective_unconscious.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@RestController
public class HomeController {

  @GetMapping("/")
  public String home(HttpServletRequest request) {
    return "seems ok";
  }

}