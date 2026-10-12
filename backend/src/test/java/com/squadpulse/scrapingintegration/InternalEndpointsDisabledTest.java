package com.squadpulse.scrapingintegration;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.AuthWebMvcTestConfig;
import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.match.ScrapeIngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Without {@code SCRAPER_SERVICE_TOKEN} the app runs, but {@code /internal/**} is closed: every
 * request is a 401, whatever bearer value it carries, and the ingest is never reached.
 */
@WebMvcTest(
    controllers = ScrapeResultController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {
      AuthWebMvcTestConfig.JWT_SECRET,
      AuthWebMvcTestConfig.PASSWORD_PEPPER,
      "squadpulse.scraper.service-token="
    })
@Import({AuthWebMvcTestConfig.class, TestAccessTokens.class, InternalSecurityConfig.class})
@EnableConfigurationProperties(ScraperProperties.class)
class InternalEndpointsDisabledTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private TestAccessTokens tokens;
  @MockitoBean private ScrapeIngestService ingestService;

  @Test
  void everyInternalRequestIsA401() throws Exception {
    for (String header :
        new String[] {
          null,
          "Bearer ",
          "Bearer anything",
          "Bearer " + "x".repeat(40),
          tokens.bearer("club-a", PermissionLevel.ADMIN)
        }) {
      var request =
          post("/internal/scrape-results").contentType(MediaType.APPLICATION_JSON).content("{}");
      if (header != null) {
        request.header("Authorization", header);
      }
      mockMvc
          .perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(jsonPath("$.message").value("Authentication required"));
    }
    verifyNoInteractions(ingestService);
  }
}
