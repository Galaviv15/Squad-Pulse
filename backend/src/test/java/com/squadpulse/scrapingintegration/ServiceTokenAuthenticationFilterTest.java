package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import com.squadpulse.common.CapturedLogs;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** The service-token check on its own: what it accepts, what it grants, what it logs. */
class ServiceTokenAuthenticationFilterTest {

  private static final String TOKEN = "test-only-scraper-token-0123456789abcdef";

  private final CapturedLogs logs = new CapturedLogs();

  @BeforeEach
  void setUp() {
    logs.start();
  }

  @AfterEach
  void tearDown() {
    logs.stop();
    SecurityContextHolder.clearContext();
  }

  @Test
  void theConfiguredTokenGrantsExactlyTheScraperAuthority() throws Exception {
    Authentication authentication = authentication(filter(TOKEN), "Bearer " + TOKEN);

    assertThat(authentication).isNotNull();
    assertThat(authentication.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("SCRAPER_SERVICE");
    assertThat(authentication(filter(TOKEN), "bearer " + TOKEN)).isNotNull();
  }

  @Test
  void anythingElseStaysUnauthenticated() throws Exception {
    ServiceTokenAuthenticationFilter filter = filter(TOKEN);
    String oneOff = TOKEN.substring(0, TOKEN.length() - 1) + "e";
    for (String header :
        new String[] {
          null, "", "Bearer ", "Bearer " + oneOff, "Bearer " + TOKEN + "f", "Basic " + TOKEN, TOKEN
        }) {
      assertThat(authentication(filter, header)).as(String.valueOf(header)).isNull();
    }
  }

  @Test
  void withoutAConfiguredTokenNothingAuthenticatesAndOneWarningIsLogged() throws Exception {
    ServiceTokenAuthenticationFilter filter =
        ServiceTokenAuthenticationFilter.create(new ScraperProperties("", null, null, null));

    assertThat(authentication(filter, "Bearer " + TOKEN)).isNull();
    assertThat(authentication(filter, "Bearer ")).isNull();
    assertThat(logs.warningsOrAbove())
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains("internal endpoints disabled");
            });
  }

  @Test
  void aConfiguredTokenLogsNothingAndIsNeverLogged() throws Exception {
    ServiceTokenAuthenticationFilter filter = filter(TOKEN);
    authentication(filter, "Bearer " + TOKEN);
    authentication(filter, "Bearer wrong");

    assertThat(logs.warningsOrAbove()).isEmpty();
    assertThat(logs.everythingLogged()).doesNotContain(TOKEN);
  }

  private static ServiceTokenAuthenticationFilter filter(String token) {
    return ServiceTokenAuthenticationFilter.create(new ScraperProperties(token, null, null, null));
  }

  private static Authentication authentication(
      ServiceTokenAuthenticationFilter filter, String header) throws Exception {
    SecurityContextHolder.clearContext();
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/scrape-results");
    if (header != null) {
      request.addHeader("Authorization", header);
    }
    filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
