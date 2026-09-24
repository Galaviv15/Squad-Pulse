package com.squadpulse.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Exposes the application's single {@link PasswordEncoder}, peppered with PASSWORD_PEPPER. */
@Configuration
class PasswordEncoderConfig {

  @Bean
  PasswordEncoder passwordEncoder(SecurityProperties securityProperties) {
    return new PepperedPasswordEncoder(securityProperties.passwordPepper());
  }
}
