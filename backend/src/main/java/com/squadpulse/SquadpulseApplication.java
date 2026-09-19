package com.squadpulse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the SquadPulse API — a single Spring Boot modular monolith.
 *
 * <p>See docs/spec.md section 02 for why this is a monolith (not microservices), and the
 * package-info.java in each module ({@code auth}, {@code squad}, {@code tactics}, {@code training},
 * {@code scrapingintegration}, {@code common}) for that module's responsibility.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SquadpulseApplication {

  public static void main(String[] args) {
    SpringApplication.run(SquadpulseApplication.class, args);
  }
}
