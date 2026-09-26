package com.squadpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * Entry point for the SquadPulse API — a single Spring Boot modular monolith.
 *
 * <p>See docs/spec.md section 02 for why this is a monolith (not microservices), and the
 * package-info.java in each module ({@code auth}, {@code squad}, {@code tactics}, {@code training},
 * {@code scrapingintegration}, {@code common}) for that module's responsibility.
 *
 * <p>{@link UserDetailsServiceAutoConfiguration} is excluded: users authenticate with a JWT (see
 * {@code auth.SecurityConfig}), never through Spring Security's {@code UserDetailsService}, so the
 * in-memory user with a generated password it would otherwise create is just an unused credential.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SquadpulseApplication {

  public static void main(String[] args) {
    SpringApplication.run(SquadpulseApplication.class, args);
  }
}
