package com.squadpulse.auth;

import com.squadpulse.common.ClubContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * What a {@code @WebMvcTest} slice needs on top of the controller under test to run the real
 * security chain: {@link SecurityConfig} with the real {@link JwtAuthenticationFilter} and {@link
 * JwtService}, so tests authenticate with genuine signed tokens rather than mocked principals. Pair
 * with the {@link #JWT_SECRET} and {@link #PASSWORD_PEPPER} properties.
 */
@TestConfiguration
@EnableConfigurationProperties({SecurityProperties.class, TokenProperties.class})
@Import({SecurityConfig.class, JwtService.class, ClubContext.class})
class AuthWebMvcTestConfig {

  /** Dummy values so the startup validation in SecurityProperties passes — not real secrets. */
  static final String JWT_SECRET =
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret";

  static final String PASSWORD_PEPPER =
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret";
}
