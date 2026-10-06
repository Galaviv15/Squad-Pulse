package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of {@link ClubController} behind the real security chain, with {@link
 * ClubSettingsService} mocked: who may call what, body validation (trim, 1–100 characters), and the
 * exact error bodies. That the write is a targeted {@code $set} and stays within the caller's club
 * is proven on a real MongoDB in {@link ClubSettingsIntegrationTest}.
 */
@WebMvcTest(
    controllers = ClubController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, TestAccessTokens.class})
class ClubControllerTest {

  private static final String URL = "/clubs/me";
  private static final AuthenticatedUser ADMIN =
      new AuthenticatedUser("user-1", "club-a", PermissionLevel.ADMIN);

  @Autowired private MockMvc mockMvc;
  @Autowired private TestAccessTokens tokens;
  @MockitoBean private ClubSettingsService clubSettingsService;

  @BeforeEach
  void stubService() {
    when(clubSettingsService.club(any()))
        .thenReturn(new ClubResponse("club-a", "Hapoel Example", true));
    when(clubSettingsService.update(any(), any()))
        .thenAnswer(
            invocation ->
                new ClubResponse(
                    "club-a", invocation.<UpdateClubRequest>getArgument(1).name(), false));
  }

  // --- permission matrix -------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} as {1} -> {2}")
  @CsvSource({
    "GET,   VIEW_ONLY,    200",
    "GET,   EDIT_PARTIAL, 200",
    "GET,   EDIT_FULL,    200",
    "GET,   ADMIN,        200",
    "GET,   NONE,         401",
    "PATCH, VIEW_ONLY,    403",
    "PATCH, EDIT_PARTIAL, 403",
    "PATCH, EDIT_FULL,    403",
    "PATCH, ADMIN,        200",
    "PATCH, NONE,         401",
  })
  void eachEndpointAdmitsExactlyTheLevelsAtOrAboveItsMinimum(
      String method, String caller, int expectedStatus) throws Exception {
    MockHttpServletRequestBuilder request =
        switch (method) {
          case "GET" -> get(URL);
          case "PATCH" -> patchBody("{\"name\": \"Hapoel Example\"}");
          default -> throw new IllegalArgumentException(method);
        };
    if (!caller.equals("NONE")) {
      request.header("Authorization", tokens.bearer("club-a", PermissionLevel.valueOf(caller)));
    }

    mockMvc.perform(request).andExpect(status().is(expectedStatus));

    if (expectedStatus == 401 || expectedStatus == 403) {
      verifyNoInteractions(clubSettingsService);
    }
  }

  @Test
  void withoutATokenBothAreTheGeneric401() throws Exception {
    for (MockHttpServletRequestBuilder request :
        List.of(get(URL), patchBody("{\"name\": \"Hapoel Example\"}"))) {
      mockMvc
          .perform(request)
          .andExpect(status().isUnauthorized())
          .andExpect(errorBody(401, "Unauthorized", "Authentication required", List.of()));
    }
  }

  @Test
  void aNonAdminPatchIsTheExact403() throws Exception {
    mockMvc
        .perform(
            patchBody("{\"name\": \"Hapoel Example\"}")
                .header("Authorization", tokens.bearer("club-a", PermissionLevel.EDIT_FULL)))
        .andExpect(status().isForbidden())
        .andExpect(errorBody(403, "Forbidden", "Access denied", List.of()));
  }

  // --- GET ---------------------------------------------------------------------------------------

  @Test
  void getReturnsExactlyIdNameAndHasLogoForTheTokensClub() throws Exception {
    String body =
        mockMvc
            .perform(
                get(URL)
                    .header("Authorization", tokens.bearer("club-a", PermissionLevel.VIEW_ONLY)))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(JsonPath.<Map<String, Object>>read(body, "$"))
        .containsExactly(
            Map.entry("id", "club-a"),
            Map.entry("name", "Hapoel Example"),
            Map.entry("hasLogo", true));
    verify(clubSettingsService)
        .club(new AuthenticatedUser("user-1", "club-a", PermissionLevel.VIEW_ONLY));
  }

  // --- PATCH: accepted bodies --------------------------------------------------------------------

  @Test
  void patchPassesTheTokensCallerAndTheNameToTheServiceAndReturnsItsResponse() throws Exception {
    mockMvc
        .perform(asAdmin(patchBody("{\"name\": \"Maccabi Example\"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("club-a"))
        .andExpect(jsonPath("$.name").value("Maccabi Example"))
        .andExpect(jsonPath("$.hasLogo").value(false));

    verify(clubSettingsService).update(ADMIN, new UpdateClubRequest("Maccabi Example"));
  }

  @Test
  void surroundingSpacesAreTrimmedBeforeTheServiceSeesTheName() throws Exception {
    mockMvc
        .perform(asAdmin(patchBody("{\"name\": \"  \\tהפועל בדיקה \\n \"}")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("הפועל בדיקה"));

    assertThat(capturedRequest().name()).isEqualTo("הפועל בדיקה");
  }

  /** The limit counts the trimmed name: 100 characters plus padding is fine. */
  @Test
  void aNameOfExactlyTheLimitIsAcceptedEvenWithPadding() throws Exception {
    String hundred = "א".repeat(Club.NAME_MAX_LENGTH);

    mockMvc
        .perform(asAdmin(patchBody("{\"name\": \"  " + hundred + "  \"}")))
        .andExpect(status().isOk());

    assertThat(capturedRequest().name()).isEqualTo(hundred);
  }

  /**
   * The installed Jackson ignores unknown properties (as on every other endpoint), so a forged
   * {@code id} / {@code clubId} is accepted but can't reach the service: the request record has
   * only {@code name}, and the club is the token's. That it changes nothing stored is proven in
   * {@link ClubSettingsIntegrationTest}.
   */
  @Test
  void unknownFieldsLikeAForgedClubIdAreIgnoredNotRejected() throws Exception {
    mockMvc
        .perform(
            asAdmin(
                patchBody(
                    "{\"name\": \"x\", \"id\": \"club-b\", \"clubId\": \"club-b\","
                        + " \"createdAt\": \"2020-01-01T00:00:00Z\", \"version\": 7}")))
        .andExpect(status().isOk());

    verify(clubSettingsService).update(ADMIN, new UpdateClubRequest("x"));
  }

  // --- PATCH: rejected bodies --------------------------------------------------------------------

  static Stream<Arguments> invalidNames() {
    return Stream.of(
        Arguments.of("missing", "{}", "name: must not be blank"),
        Arguments.of("null", "{\"name\": null}", "name: must not be blank"),
        Arguments.of("empty", "{\"name\": \"\"}", "name: must not be blank"),
        Arguments.of("whitespace only", "{\"name\": \" \\t\\n \"}", "name: must not be blank"),
        Arguments.of(
            "101 characters",
            "{\"name\": \"" + "a".repeat(Club.NAME_MAX_LENGTH + 1) + "\"}",
            "name: size must be between 0 and 100"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidNames")
  void anInvalidNameIsTheExact400(String description, String body, String detail) throws Exception {
    mockMvc
        .perform(asAdmin(patchBody(body)))
        .andExpect(status().isBadRequest())
        .andExpect(
            errorBody(400, "Validation Failed", "Request validation failed", List.of(detail)));

    verify(clubSettingsService, never()).update(any(), any());
  }

  /**
   * Pins the installed Jackson's behavior as Boot configures it (no custom deserializer): a JSON
   * scalar where a string is expected is coerced to its text, so it's an ordinary name — accepted,
   * and validated like any other string.
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {"{\"name\": 123}   | 123", "{\"name\": 1.5}   | 1.5", "{\"name\": true}  | true"})
  void aScalarNameIsCoercedToItsText(String body, String coerced) throws Exception {
    mockMvc.perform(asAdmin(patchBody(body))).andExpect(status().isOk());

    verify(clubSettingsService).update(ADMIN, new UpdateClubRequest(coerced));
  }

  /**
   * An array or object can't be bound to a string at all (single-element arrays aren't unwrapped):
   * a malformed body, the value never echoed.
   */
  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"{\"name\": [\"x\"]}", "{\"name\": {\"a\": 1}}", "{\"name\": {\"secret\": 1}}"})
  void anArrayOrObjectNameIsAMalformedBody(String body) throws Exception {
    mockMvc
        .perform(asAdmin(patchBody(body)))
        .andExpect(status().isBadRequest())
        .andExpect(
            errorBody(400, "Bad Request", "Malformed request body", List.of("name: invalid value")))
        .andExpect(content().string(not(containsString("secret"))));

    verify(clubSettingsService, never()).update(any(), any());
  }

  // --- PATCH: caller and club failures -----------------------------------------------------------

  /** The caller re-check: the generic 401, the same body as a request without a token. */
  @Test
  void aCallerWhoCanNoLongerActGetsTheGeneric401() throws Exception {
    doThrow(new CurrentUserUnavailableException()).when(clubSettingsService).update(any(), any());

    mockMvc
        .perform(asAdmin(patchBody("{\"name\": \"Maccabi Example\"}")))
        .andExpect(status().isUnauthorized())
        .andExpect(errorBody(401, "Unauthorized", "Authentication required", List.of()));
  }

  /** A missing club is a data-integrity bug: the generic 500, never the exception's message. */
  @Test
  void aMissingClubIsAGeneric500() throws Exception {
    doThrow(new IllegalStateException("Club club-a not found"))
        .when(clubSettingsService)
        .update(any(), any());

    mockMvc
        .perform(asAdmin(patchBody("{\"name\": \"Maccabi Example\"}")))
        .andExpect(status().isInternalServerError())
        .andExpect(
            errorBody(500, "Internal Server Error", "An unexpected error occurred", List.of()))
        .andExpect(content().string(not(containsString("club-a"))));
  }

  // --- helpers -----------------------------------------------------------------------------------

  private UpdateClubRequest capturedRequest() {
    ArgumentCaptor<UpdateClubRequest> request = ArgumentCaptor.forClass(UpdateClubRequest.class);
    verify(clubSettingsService).update(eq(ADMIN), request.capture());
    return request.getValue();
  }

  private static MockHttpServletRequestBuilder patchBody(String json) {
    return patch(URL).contentType(MediaType.APPLICATION_JSON).content(json);
  }

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.ADMIN));
  }

  /**
   * The whole error body, {@code timestamp} aside: exactly these fields, nothing more ({@code code}
   * present and null).
   */
  private static ResultMatcher errorBody(
      int status, String error, String message, List<String> details) {
    return result -> {
      Map<String, Object> body =
          new HashMap<>(
              JsonPath.<Map<String, Object>>read(result.getResponse().getContentAsString(), "$"));
      assertThat(body.remove("timestamp")).as("timestamp").isNotNull();
      Map<String, Object> expected = new HashMap<>();
      expected.put("status", status);
      expected.put("error", error);
      expected.put("code", null);
      expected.put("message", message);
      expected.put("details", details);
      assertThat(body).isEqualTo(expected);
    };
  }
}
