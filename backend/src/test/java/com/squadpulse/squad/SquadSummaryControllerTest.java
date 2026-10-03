package com.squadpulse.squad;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.AuthWebMvcTestConfig;
import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link SquadSummaryController} behind the real security chain, with {@link
 * PlayerService} mocked: who may call it, and exactly how the response is written by the
 * application's own JSON mapper. What the service computes is covered in {@link
 * PlayerServiceSummaryTest}, and on a real MongoDB in {@link PlayerApiIntegrationTest}.
 */
@WebMvcTest(
    controllers = SquadSummaryController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, TestAccessTokens.class})
class SquadSummaryControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private TestAccessTokens tokens;
  @MockitoBean private PlayerService playerService;

  @BeforeEach
  void stubService() {
    when(playerService.summary()).thenReturn(summary(23, new BigDecimal("26.4"), 3, 8, 7, 5));
  }

  /** Nothing ranks below {@code VIEW_ONLY}, so every authenticated caller gets in. */
  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"VIEW_ONLY, 200", "EDIT_PARTIAL, 200", "EDIT_FULL, 200", "ADMIN, 200", "NONE, 401"})
  void everyLevelMayReadTheSummaryButNotAnAnonymousCaller(String caller, int expectedStatus)
      throws Exception {
    MockHttpServletRequestBuilder request = get("/squad/summary");
    if (!caller.equals("NONE")) {
      request.header("Authorization", tokens.bearer("club-a", PermissionLevel.valueOf(caller)));
    }

    mockMvc.perform(request).andExpect(status().is(expectedStatus));
  }

  @Test
  void theResponseHasExactlyTheseFieldsWithEveryLineInOrder() throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/summary")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    "{\"playerCount\":23,\"averageAge\":26.4,\"lines\":{\"GOALKEEPERS\":3,"
                        + "\"DEFENSE\":8,\"MIDFIELD\":7,\"ATTACK\":5}}"));
    verify(playerService).summary();
  }

  @Test
  void aMissingAverageIsWrittenAsNullNotOmitted() throws Exception {
    when(playerService.summary()).thenReturn(summary(0, null, 0, 0, 0, 0));

    mockMvc
        .perform(asViewer(get("/squad/summary")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    "{\"playerCount\":0,\"averageAge\":null,\"lines\":{\"GOALKEEPERS\":0,"
                        + "\"DEFENSE\":0,\"MIDFIELD\":0,\"ATTACK\":0}}"));
  }

  /** The service gives a whole-number average scale 1 ({@code 26.0}); it stays a plain number. */
  @Test
  void aWholeNumberAverageIsAPlainJsonNumber() throws Exception {
    when(playerService.summary()).thenReturn(summary(1, new BigDecimal("26.0"), 0, 0, 0, 1));

    mockMvc
        .perform(asViewer(get("/squad/summary")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    "{\"playerCount\":1,\"averageAge\":26.0,\"lines\":{\"GOALKEEPERS\":0,"
                        + "\"DEFENSE\":0,\"MIDFIELD\":0,\"ATTACK\":1}}"));
  }

  private static SquadSummaryResponse summary(
      int playerCount,
      BigDecimal averageAge,
      int goalkeepers,
      int defense,
      int midfield,
      int attack) {
    Map<Line, Integer> lines = new EnumMap<>(Line.class);
    lines.put(Line.GOALKEEPERS, goalkeepers);
    lines.put(Line.DEFENSE, defense);
    lines.put(Line.MIDFIELD, midfield);
    lines.put(Line.ATTACK, attack);
    return new SquadSummaryResponse(playerCount, averageAge, lines);
  }

  private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.VIEW_ONLY));
  }
}
