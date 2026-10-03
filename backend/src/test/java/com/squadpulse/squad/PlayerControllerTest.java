package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.AuthWebMvcTestConfig;
import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.common.ImageProperties;
import com.squadpulse.common.ImageType;
import com.squadpulse.common.ImageValidator;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.common.StoredImage;
import com.squadpulse.common.TestImages;
import com.squadpulse.common.ValidatedImage;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP contract of {@link PlayerController} behind the real security chain, with {@link
 * PlayerService} mocked: who may call what, request validation (body, query string and photo
 * uploads, with the real {@link ImageValidator}), the 201 / 204 / 404 / 409 / 413 mappings, and the
 * response shape. That the service really stays within the caller's club, and what it writes, is
 * proven on a real MongoDB in {@link PlayerApiIntegrationTest}.
 */
@WebMvcTest(
    controllers = PlayerController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, TestAccessTokens.class, ImageValidator.class})
@EnableConfigurationProperties(ImageProperties.class)
class PlayerControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int TWO_MIB = 2 * 1024 * 1024;
  private static final String STALE_MESSAGE =
      "This player was changed by someone else since you loaded it; reload it and apply your"
          + " changes again";

  @Autowired private MockMvc mockMvc;
  @Autowired private TestAccessTokens tokens;
  @MockitoBean private PlayerService playerService;

  @BeforeEach
  void stubService() {
    when(playerService.list(any())).thenReturn(List.of(player()));
    when(playerService.get(anyString())).thenReturn(player());
    when(playerService.create(any())).thenReturn(player());
    when(playerService.update(anyString(), any())).thenReturn(player());
    when(playerService.release(anyString(), any())).thenReturn(player());
    when(playerService.reactivate(anyString(), any())).thenReturn(player());
    when(playerService.photo(anyString()))
        .thenAnswer(
            invocation ->
                new StoredImage(
                    ImageType.PNG,
                    TestImages.png().length,
                    new ByteArrayInputStream(TestImages.png())));
  }

  // --- permission matrix -------------------------------------------------------------------------

  @ParameterizedTest(name = "{0} {1} as {2} -> {3}")
  @CsvSource({
    "GET,  /squad/players,      VIEW_ONLY,    200",
    "GET,  /squad/players,      EDIT_PARTIAL, 200",
    "GET,  /squad/players,      EDIT_FULL,    200",
    "GET,  /squad/players,      ADMIN,        200",
    "GET,  /squad/players,      NONE,         401",
    "GET,  /squad/players/p-1,  VIEW_ONLY,    200",
    "GET,  /squad/players/p-1,  EDIT_PARTIAL, 200",
    "GET,  /squad/players/p-1,  EDIT_FULL,    200",
    "GET,  /squad/players/p-1,  ADMIN,        200",
    "GET,  /squad/players/p-1,  NONE,         401",
    "POST, /squad/players,      VIEW_ONLY,    403",
    "POST, /squad/players,      EDIT_PARTIAL, 403",
    "POST, /squad/players,      EDIT_FULL,    201",
    "POST, /squad/players,      ADMIN,        201",
    "POST, /squad/players,      NONE,         401",
    "PUT,  /squad/players/p-1,  VIEW_ONLY,    403",
    "PUT,  /squad/players/p-1,  EDIT_PARTIAL, 403",
    "PUT,  /squad/players/p-1,  EDIT_FULL,    200",
    "PUT,  /squad/players/p-1,  ADMIN,        200",
    "PUT,  /squad/players/p-1,  NONE,         401",
    "POST, /squad/players/p-1/release,     VIEW_ONLY,    403",
    "POST, /squad/players/p-1/release,     EDIT_PARTIAL, 403",
    "POST, /squad/players/p-1/release,     EDIT_FULL,    200",
    "POST, /squad/players/p-1/release,     ADMIN,        200",
    "POST, /squad/players/p-1/release,     NONE,         401",
    "POST, /squad/players/p-1/reactivate,  VIEW_ONLY,    403",
    "POST, /squad/players/p-1/reactivate,  EDIT_PARTIAL, 403",
    "POST, /squad/players/p-1/reactivate,  EDIT_FULL,    200",
    "POST, /squad/players/p-1/reactivate,  ADMIN,        200",
    "POST, /squad/players/p-1/reactivate,  NONE,         401",
    "DELETE, /squad/players/p-1,           VIEW_ONLY,    403",
    "DELETE, /squad/players/p-1,           EDIT_PARTIAL, 403",
    "DELETE, /squad/players/p-1,           EDIT_FULL,    403",
    "DELETE, /squad/players/p-1,           ADMIN,        204",
    "DELETE, /squad/players/p-1,           NONE,         401",
    "PUT,    /squad/players/p-1/photo,     VIEW_ONLY,    403",
    "PUT,    /squad/players/p-1/photo,     EDIT_PARTIAL, 403",
    "PUT,    /squad/players/p-1/photo,     EDIT_FULL,    204",
    "PUT,    /squad/players/p-1/photo,     ADMIN,        204",
    "PUT,    /squad/players/p-1/photo,     NONE,         401",
    "GET,    /squad/players/p-1/photo,     VIEW_ONLY,    200",
    "GET,    /squad/players/p-1/photo,     EDIT_PARTIAL, 200",
    "GET,    /squad/players/p-1/photo,     EDIT_FULL,    200",
    "GET,    /squad/players/p-1/photo,     ADMIN,        200",
    "GET,    /squad/players/p-1/photo,     NONE,         401",
    "DELETE, /squad/players/p-1/photo,     VIEW_ONLY,    403",
    "DELETE, /squad/players/p-1/photo,     EDIT_PARTIAL, 403",
    "DELETE, /squad/players/p-1/photo,     EDIT_FULL,    204",
    "DELETE, /squad/players/p-1/photo,     ADMIN,        204",
    "DELETE, /squad/players/p-1/photo,     NONE,         401",
  })
  void eachEndpointAdmitsExactlyTheLevelsAtOrAboveItsMinimum(
      String method, String path, String caller, int expectedStatus) throws Exception {
    AbstractMockHttpServletRequestBuilder<?> request =
        switch (method) {
          case "GET" -> get(path);
          case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON).content(postBody(path));
          case "PUT" ->
              path.endsWith("/photo")
                  ? photoUpload(path, TestImages.png())
                  : put(path).contentType(MediaType.APPLICATION_JSON).content(updateBody());
          case "DELETE" -> delete(path);
          default -> throw new IllegalArgumentException(method);
        };
    if (!caller.equals("NONE")) {
      request.header("Authorization", tokens.bearer("club-a", PermissionLevel.valueOf(caller)));
    }

    mockMvc.perform(request).andExpect(status().is(expectedStatus));

    if (expectedStatus == 403) {
      verifyNoInteractions(playerService);
    }
  }

  // --- list --------------------------------------------------------------------------------------

  @Test
  void listPassesEveryFilterToTheServiceAndDefaultsToActive() throws Exception {
    mockMvc.perform(asViewer(get("/squad/players"))).andExpect(status().isOk());
    verify(playerService).list(new PlayerFilter(PlayerStatus.ACTIVE, null, null, null, null, null));

    mockMvc
        .perform(
            asViewer(
                get(
                    "/squad/players?status=released&position=CB&minAge=20&maxAge=30"
                        + "&medicalStatus=INJURED&preferredFoot=LEFT")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value("p-1"));
    verify(playerService)
        .list(
            new PlayerFilter(
                PlayerStatus.RELEASED,
                Position.CB,
                20,
                30,
                MedicalStatus.INJURED,
                PreferredFoot.LEFT));
  }

  @Test
  void theStatusFilterAcceptsAll() throws Exception {
    mockMvc.perform(asViewer(get("/squad/players?status=all"))).andExpect(status().isOk());
    verify(playerService).list(new PlayerFilter(PlayerStatus.ALL, null, null, null, null, null));
  }

  static Stream<Arguments> invalidQueryParams() {
    return Stream.of(
        Arguments.of(
            "position=XX", "position: must be one of [GK, CB, RB, LB, DM, CM, AM, RW, LW, ST]"),
        Arguments.of("status=ACTIVE", "status: must be one of [active, released, all]"),
        Arguments.of("status=gone", "status: must be one of [active, released, all]"),
        Arguments.of("medicalStatus=HURT", "medicalStatus: must be one of [FIT, INJURED]"),
        Arguments.of("preferredFoot=NONE", "preferredFoot: must be one of [RIGHT, LEFT, BOTH]"),
        Arguments.of("minAge=abc", "minAge: has an invalid format"),
        Arguments.of("minAge=17", "minAge: must be greater than or equal to 18"),
        Arguments.of("maxAge=100", "maxAge: must be less than or equal to 99"));
  }

  /** Each of these used to fall through to the catch-all handler as a 500. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidQueryParams")
  void anInvalidQueryParamIs400NamingIt(String query, String detail) throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/players?" + query)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"))
        .andExpect(jsonPath("$.message").value("Request validation failed"))
        .andExpect(jsonPath("$.details").value(contains(detail)));
    verify(playerService, never()).list(any());
  }

  @Test
  void everyInvalidQueryParamGetsItsOwnDetail() throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/players?minAge=10&maxAge=120")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.details.length()").value(2))
        .andExpect(jsonPath("$.details").value(hasItem(startsWith("minAge: "))))
        .andExpect(jsonPath("$.details").value(hasItem(startsWith("maxAge: "))));
  }

  @Test
  void aMinAgeAboveTheMaxAgeIs400() throws Exception {
    when(playerService.list(any())).thenThrow(new InvalidAgeRangeException());

    mockMvc
        .perform(asViewer(get("/squad/players?minAge=30&maxAge=20")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"))
        .andExpect(jsonPath("$.message").value("Request validation failed"))
        .andExpect(
            jsonPath("$.details").value(contains("minAge: must be less than or equal to maxAge")));
  }

  // --- get ---------------------------------------------------------------------------------------

  @Test
  void getReturnsEveryFieldButNeverTheClubId() throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/players/p-1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("p-1"))
        .andExpect(jsonPath("$.fullName").value("Eran Zahavi"))
        .andExpect(jsonPath("$.primaryPosition").value("ST"))
        .andExpect(jsonPath("$.secondaryPosition").value("AM"))
        .andExpect(jsonPath("$.jerseyNumber").value(7))
        .andExpect(jsonPath("$.dateOfBirth").value("1995-05-20"))
        .andExpect(jsonPath("$.heightCm").value(180))
        .andExpect(jsonPath("$.weightKg").value(75))
        .andExpect(jsonPath("$.preferredFoot").value("RIGHT"))
        .andExpect(jsonPath("$.medicalStatus").value("FIT"))
        .andExpect(jsonPath("$.active").value(true))
        .andExpect(jsonPath("$.version").hasJsonPath())
        .andExpect(jsonPath("$.createdAt").hasJsonPath())
        .andExpect(jsonPath("$.updatedAt").hasJsonPath())
        .andExpect(jsonPath("$.hasPhoto").value(false))
        .andExpect(content().string(not(containsString("clubId"))))
        .andExpect(content().string(not(containsString("club-a"))));
  }

  @Test
  void anUnknownPlayerIs404() throws Exception {
    when(playerService.get("nope")).thenThrow(new NotFoundException("Player not found"));

    mockMvc
        .perform(asViewer(get("/squad/players/nope")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
  }

  // --- create ------------------------------------------------------------------------------------

  @Test
  void createIs201WithTheNewPlayersLocationAndNoClubId() throws Exception {
    mockMvc
        .perform(asEditor(post("/squad/players"), createBody()))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/squad/players/p-1"))
        .andExpect(jsonPath("$.id").value("p-1"))
        .andExpect(content().string(not(containsString("clubId"))));
  }

  @Test
  void createPassesTheBodyThroughWithANameTrimmedBeforeValidation() throws Exception {
    String hundredChars = "a".repeat(100);
    Map<String, Object> body = createFields();
    body.put("fullName", "  " + hundredChars + "  ");
    body.remove("medicalStatus");

    mockMvc.perform(asEditor(post("/squad/players"), json(body))).andExpect(status().isCreated());

    ArgumentCaptor<CreatePlayerRequest> request =
        ArgumentCaptor.forClass(CreatePlayerRequest.class);
    verify(playerService).create(request.capture());
    assertRequest(request.getValue(), hundredChars);
  }

  /**
   * Unknown properties are ignored (Jackson 3 default, which Boot keeps), so a forged {@code
   * clubId} / {@code active} / {@code id} can't reach the service: the request records have no such
   * components. That they change nothing stored is proven in {@link PlayerApiIntegrationTest}.
   */
  @Test
  void forbiddenBodyFieldsAreIgnoredNotRejected() throws Exception {
    Map<String, Object> body = createFields();
    body.put("clubId", "club-b");
    body.put("active", false);
    body.put("id", "forged-id");
    body.put("createdAt", "2020-01-01T00:00:00Z");

    mockMvc.perform(asEditor(post("/squad/players"), json(body))).andExpect(status().isCreated());
    verify(playerService).create(any());
  }

  static Stream<Arguments> invalidCreateBodies() {
    return Stream.concat(
        commonInvalidFields(),
        Stream.of(
            // An unknown enum value can't be bound at all: a malformed body, not a field error.
            Arguments.of("primaryPosition", "XX")));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidCreateBodies")
  void anInvalidCreateBodyIs400NamingTheField(String field, Object value) throws Exception {
    Map<String, Object> body = createFields();
    body.put(field, value);

    ResultActions result = mockMvc.perform(asEditor(post("/squad/players"), json(body)));

    expectFieldError(result, field, value);
    verify(playerService, never()).create(any());
  }

  /** Broken JSON has no property to blame: the generic message and no details. */
  @Test
  void brokenJsonIs400WithoutDetails() throws Exception {
    mockMvc
        .perform(asEditor(post("/squad/players"), "{\"fullName\": \"Eran\","))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("Malformed request body"))
        .andExpect(jsonPath("$.details").isEmpty());
    verify(playerService, never()).create(any());
  }

  // --- update ------------------------------------------------------------------------------------

  static Stream<Arguments> invalidUpdateBodies() {
    return Stream.concat(
        commonInvalidFields(),
        Stream.of(Arguments.of("medicalStatus", null), Arguments.of("version", null)));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidUpdateBodies")
  void anInvalidUpdateBodyIs400NamingTheField(String field, Object value) throws Exception {
    Map<String, Object> body = updateFields();
    body.put(field, value);

    ResultActions result = mockMvc.perform(asEditor(put("/squad/players/p-1"), json(body)));

    expectFieldError(result, field, value);
    verify(playerService, never()).update(anyString(), any());
  }

  @Test
  void aMissingVersionOrMedicalStatusIs400() throws Exception {
    for (String field : List.of("version", "medicalStatus")) {
      Map<String, Object> body = updateFields();
      body.remove(field);

      mockMvc
          .perform(asEditor(put("/squad/players/p-1"), json(body)))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.details").value(hasItem(startsWith(field + ": "))));
    }
    verify(playerService, never()).update(anyString(), any());
  }

  @Test
  void updatePassesTheIdAndBodyThrough() throws Exception {
    mockMvc
        .perform(asEditor(put("/squad/players/p-1"), updateBody()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("p-1"))
        .andExpect(content().string(not(containsString("clubId"))));

    ArgumentCaptor<UpdatePlayerRequest> request =
        ArgumentCaptor.forClass(UpdatePlayerRequest.class);
    verify(playerService).update(eq("p-1"), request.capture());
    assertThat(request.getValue().version()).isEqualTo(3L);
    assertThat(request.getValue().medicalStatus()).isEqualTo(MedicalStatus.INJURED);
  }

  static Stream<Arguments> updateConflicts() {
    return Stream.of(
        Arguments.of(
            new JerseyNumberTakenException(7),
            "Jersey number 7 is already taken by another active player"),
        Arguments.of(
            new ReleasedPlayerException(),
            "This player has been released; re-activate them before editing"),
        Arguments.of(
            new StalePlayerVersionException(),
            "This player was changed by someone else since you loaded it; reload it and apply"
                + " your changes again"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("updateConflicts")
  void updateConflictsAre409(RuntimeException conflict, String message) throws Exception {
    when(playerService.update(anyString(), any())).thenThrow(conflict);

    mockMvc
        .perform(asEditor(put("/squad/players/p-1"), updateBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value(message));
  }

  @Test
  void aJerseyClashOnCreateIs409() throws Exception {
    when(playerService.create(any())).thenThrow(new JerseyNumberTakenException(7));

    mockMvc
        .perform(asEditor(post("/squad/players"), createBody()))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("Jersey number 7 is already taken by another active player"));
  }

  @Test
  void updatingAnUnknownPlayerIs404() throws Exception {
    when(playerService.update(eq("nope"), any()))
        .thenThrow(new NotFoundException("Player not found"));

    mockMvc
        .perform(asEditor(put("/squad/players/nope"), updateBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
  }

  // --- release / reactivate ---------------------------------------------------------------------

  @Test
  void releasePassesTheIdAndVersionThrough() throws Exception {
    mockMvc
        .perform(asEditor(post("/squad/players/p-1/release"), "{\"version\": 3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("p-1"))
        .andExpect(content().string(not(containsString("clubId"))));

    verify(playerService).release("p-1", new ReleasePlayerRequest(3L));
  }

  @Test
  void reactivatePassesTheIdVersionAndNumberThrough() throws Exception {
    mockMvc
        .perform(
            asEditor(
                post("/squad/players/p-1/reactivate"), "{\"version\": 3, \"jerseyNumber\": 11}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("p-1"));

    verify(playerService).reactivate("p-1", new ReactivatePlayerRequest(3L, 11));
  }

  /**
   * Verify 2: an absent and an explicit-null {@code jerseyNumber} both reach the service as {@code
   * null} ("no number"), and unknown fields — including a forged {@code active} / {@code clubId} —
   * are ignored rather than rejected.
   */
  @Test
  void anAbsentOrNullNumberMeansNoneAndUnknownFieldsAreIgnored() throws Exception {
    for (String body :
        List.of(
            "{\"version\": 3}",
            "{\"version\": 3, \"jerseyNumber\": null}",
            "{\"version\": 3, \"active\": false, \"clubId\": \"club-b\", \"x\": 1}")) {
      mockMvc
          .perform(asEditor(post("/squad/players/p-1/reactivate"), body))
          .andExpect(status().isOk());
    }
    verify(playerService, times(3)).reactivate("p-1", new ReactivatePlayerRequest(3L, null));

    mockMvc
        .perform(
            asEditor(
                post("/squad/players/p-1/release"),
                "{\"version\": 3, \"active\": true, \"clubId\": \"club-b\"}"))
        .andExpect(status().isOk());
    verify(playerService).release("p-1", new ReleasePlayerRequest(3L));
  }

  static Stream<Arguments> invalidLifecycleBodies() {
    return Stream.of(
        Arguments.of("release", "{}", "version"),
        Arguments.of("release", "{\"version\": null}", "version"),
        Arguments.of("reactivate", "{\"jerseyNumber\": 7}", "version"),
        Arguments.of("reactivate", "{\"version\": 3, \"jerseyNumber\": 0}", "jerseyNumber"),
        Arguments.of("reactivate", "{\"version\": 3, \"jerseyNumber\": 100}", "jerseyNumber"));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("invalidLifecycleBodies")
  void anInvalidLifecycleBodyIs400NamingTheField(String action, String body, String field)
      throws Exception {
    mockMvc
        .perform(asEditor(post("/squad/players/p-1/" + action), body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"))
        .andExpect(jsonPath("$.details").value(contains(startsWith(field + ": "))));
    verifyNoInteractions(playerService);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"release", "reactivate"})
  void anUnreadableVersionIs400NamingItWithoutEchoingTheValue(String action) throws Exception {
    mockMvc
        .perform(asEditor(post("/squad/players/p-1/" + action), "{\"version\": \"seven\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Malformed request body"))
        .andExpect(jsonPath("$.details").value(contains("version: invalid value")))
        .andExpect(content().string(not(containsString("seven"))));
    verifyNoInteractions(playerService);
  }

  /**
   * Verify 4: a POST with no body at all is a 400 (Spring's "required request body is missing"),
   * not a 500 — with a JSON content type and with none.
   */
  @ParameterizedTest(name = "{0}, JSON content type: {1}")
  @CsvSource({"release, true", "release, false", "reactivate, true", "reactivate, false"})
  void aMissingBodyIs400(String action, boolean jsonContentType) throws Exception {
    MockHttpServletRequestBuilder request =
        post("/squad/players/p-1/" + action)
            .header("Authorization", tokens.bearer("club-a", PermissionLevel.EDIT_FULL));
    if (jsonContentType) {
      request.contentType(MediaType.APPLICATION_JSON);
    }

    mockMvc
        .perform(request)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Bad Request"))
        .andExpect(jsonPath("$.message").value("Malformed request body"));
    verifyNoInteractions(playerService);
  }

  static Stream<Arguments> lifecycleConflicts() {
    return Stream.of(
        Arguments.of(
            "release",
            new PlayerAlreadyReleasedException(),
            "This player has already been released"),
        Arguments.of("release", new StalePlayerVersionException(), STALE_MESSAGE),
        Arguments.of(
            "reactivate", new PlayerAlreadyActiveException(), "This player is already active"),
        Arguments.of(
            "reactivate",
            new JerseyNumberTakenException(7),
            "Jersey number 7 is already taken by another active player"),
        Arguments.of("reactivate", new StalePlayerVersionException(), STALE_MESSAGE));
  }

  @ParameterizedTest(name = "{0}: {1}")
  @MethodSource("lifecycleConflicts")
  void lifecycleConflictsAre409(String action, RuntimeException conflict, String message)
      throws Exception {
    when(playerService.release(anyString(), any())).thenThrow(conflict);
    when(playerService.reactivate(anyString(), any())).thenThrow(conflict);

    mockMvc
        .perform(asEditor(post("/squad/players/p-1/" + action), "{\"version\": 3}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(jsonPath("$.message").value(message));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"release", "reactivate"})
  void aLifecycleChangeToAnUnknownPlayerIs404(String action) throws Exception {
    when(playerService.release(eq("nope"), any()))
        .thenThrow(new NotFoundException("Player not found"));
    when(playerService.reactivate(eq("nope"), any()))
        .thenThrow(new NotFoundException("Player not found"));

    mockMvc
        .perform(asEditor(post("/squad/players/nope/" + action), "{\"version\": 3}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
  }

  // --- delete ------------------------------------------------------------------------------------

  @Test
  void deleteIs204WithNoBody() throws Exception {
    mockMvc
        .perform(asAdmin(delete("/squad/players/p-1")))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(playerService).delete("p-1");
  }

  @Test
  void deletingAnUnknownPlayerIs404() throws Exception {
    doThrow(new NotFoundException("Player not found")).when(playerService).delete("nope");

    mockMvc
        .perform(asAdmin(delete("/squad/players/nope")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Player not found"));
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** Field rules shared by create and update: {field, invalid value}. */
  // --- photo -------------------------------------------------------------------------------------

  /** The type comes from the bytes alone: a PNG declared as {@code image/jpeg} is a PNG. */
  @Test
  void uploadIs204AndPassesTheImageWithItsDetectedTypeToTheService() throws Exception {
    mockMvc
        .perform(
            asPhotoEditor(
                multipart(HttpMethod.PUT, "/squad/players/p-1/photo")
                    .file(new MockMultipartFile("file", "x.jpg", "image/jpeg", TestImages.png()))))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    ArgumentCaptor<ValidatedImage> image = ArgumentCaptor.forClass(ValidatedImage.class);
    verify(playerService).uploadPhoto(eq("p-1"), image.capture());
    assertThat(image.getValue().type()).isEqualTo(ImageType.PNG);
    assertThat(image.getValue().content()).isEqualTo(TestImages.png());
  }

  @Test
  void anImageOfExactlyTheLimitIsAccepted() throws Exception {
    mockMvc
        .perform(asPhotoEditor(photoUpload("/squad/players/p-1/photo", jpegOfSize(TWO_MIB))))
        .andExpect(status().isNoContent());
  }

  /**
   * The application's own limit. MockMvc doesn't enforce the container's multipart limits, so this
   * is the in-code check; the container's is proven on a real server in {@code
   * PlayerPhotoUploadLimitIntegrationTest}.
   */
  @Test
  void anImageOneByteOverTheLimitIs413() throws Exception {
    mockMvc
        .perform(asPhotoEditor(photoUpload("/squad/players/p-1/photo", jpegOfSize(TWO_MIB + 1))))
        .andExpect(status().is(413))
        .andExpect(jsonPath("$.status").value(413))
        .andExpect(jsonPath("$.error").value("Content Too Large"))
        .andExpect(jsonPath("$.message").value("Image must be at most 2 MB"))
        .andExpect(jsonPath("$.details").isEmpty());

    verify(playerService, never()).uploadPhoto(anyString(), any());
  }

  static Stream<Arguments> invalidPhotos() {
    return Stream.of(
        Arguments.of("empty", new byte[0], "file: must not be empty"),
        Arguments.of("SVG", TestImages.svg(), "file: must be a JPEG, PNG or WebP image"),
        Arguments.of(
            "HTML",
            "<html><body>evil-marker</body></html>".getBytes(),
            "file: must be a JPEG, PNG or WebP image"));
  }

  /** Neither the content nor the client's filename or declared type is echoed back. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidPhotos")
  void anInvalidPhotoIs400NamingTheFileField(String description, byte[] content, String detail)
      throws Exception {
    mockMvc
        .perform(
            asPhotoEditor(
                multipart(HttpMethod.PUT, "/squad/players/p-1/photo")
                    .file(new MockMultipartFile("file", "secret-name.png", "image/png", content))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"))
        .andExpect(jsonPath("$.details").value(contains(detail)))
        .andExpect(content().string(not(containsString("secret-name"))))
        .andExpect(content().string(not(containsString("svg"))))
        .andExpect(content().string(not(containsString("evil-marker"))))
        .andExpect(content().string(not(containsString("image/png"))));

    verify(playerService, never()).uploadPhoto(anyString(), any());
  }

  @Test
  void aMissingFilePartIs400() throws Exception {
    mockMvc
        .perform(
            asPhotoEditor(
                multipart(HttpMethod.PUT, "/squad/players/p-1/photo")
                    .file(new MockMultipartFile("photo", "x.png", "image/png", TestImages.png()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"))
        .andExpect(jsonPath("$.details").value(contains("file: is required")));
  }

  /**
   * A request that isn't multipart at all is a {@code MultipartException} — 400, not a 500. (No
   * {@code consumes} on the mapping, which would make it a 415.)
   */
  @ParameterizedTest(name = "{0}")
  @CsvSource({"application/json, {}", "image/png, not-really-a-png"})
  void aNonMultipartUploadIs400(String contentType, String body) throws Exception {
    mockMvc
        .perform(
            asPhotoEditor(put("/squad/players/p-1/photo"))
                .contentType(MediaType.parseMediaType(contentType))
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Malformed multipart request"))
        .andExpect(jsonPath("$.details").isEmpty());

    verify(playerService, never()).uploadPhoto(anyString(), any());
  }

  @Test
  void aPhotoForAReleasedPlayerIs409() throws Exception {
    doThrow(new ReleasedPlayerException()).when(playerService).uploadPhoto(eq("p-1"), any());
    doThrow(new ReleasedPlayerException()).when(playerService).deletePhoto("p-1");

    mockMvc
        .perform(asPhotoEditor(photoUpload("/squad/players/p-1/photo", TestImages.png())))
        .andExpect(status().isConflict())
        .andExpect(
            jsonPath("$.message")
                .value("This player has been released; re-activate them before editing"));
    mockMvc
        .perform(asPhotoEditor(delete("/squad/players/p-1/photo")))
        .andExpect(status().isConflict());
  }

  @Test
  void aPhotoOfAnUnknownPlayerIs404() throws Exception {
    NotFoundException notFound = new NotFoundException("Player not found");
    doThrow(notFound).when(playerService).uploadPhoto(eq("nope"), any());
    doThrow(notFound).when(playerService).deletePhoto("nope");
    when(playerService.photo("nope")).thenThrow(notFound);

    mockMvc
        .perform(asPhotoEditor(photoUpload("/squad/players/nope/photo", TestImages.png())))
        .andExpect(status().isNotFound());
    mockMvc.perform(asViewer(get("/squad/players/nope/photo"))).andExpect(status().isNotFound());
    mockMvc
        .perform(asPhotoEditor(delete("/squad/players/nope/photo")))
        .andExpect(status().isNotFound());
  }

  /**
   * The bytes with their stored type and length; {@code nosniff} and {@code no-store} come from
   * Spring Security's default headers, unchanged by {@code SecurityConfig}.
   */
  @Test
  void thePhotoIsServedWithItsTypeLengthAndNosniff() throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/players/p-1/photo")))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(header().longValue("Content-Length", TestImages.png().length))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("Cache-Control", containsString("no-store")))
        .andExpect(content().bytes(TestImages.png()));
  }

  @Test
  void noPhotoIs404() throws Exception {
    when(playerService.photo("p-1")).thenThrow(new NotFoundException("This player has no photo"));

    mockMvc
        .perform(asViewer(get("/squad/players/p-1/photo")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("This player has no photo"));
  }

  @Test
  void deletingThePhotoIs204() throws Exception {
    mockMvc
        .perform(asPhotoEditor(delete("/squad/players/p-1/photo")))
        .andExpect(status().isNoContent())
        .andExpect(content().string(""));

    verify(playerService).deletePhoto("p-1");
  }

  // --- hasPhoto ----------------------------------------------------------------------------------

  /** One lookup for the whole list, never one per player. */
  @Test
  void theListFlagsPhotosFromOneLookup() throws Exception {
    Player other = player();
    other.setId("p-2");
    when(playerService.list(any())).thenReturn(List.of(player(), other));
    when(playerService.playerIdsWithPhoto()).thenReturn(Set.of("p-2"));

    mockMvc
        .perform(asViewer(get("/squad/players")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].hasPhoto").value(false))
        .andExpect(jsonPath("$[1].hasPhoto").value(true));

    verify(playerService, times(1)).playerIdsWithPhoto();
    verify(playerService, never()).hasPhoto(any());
  }

  @Test
  void singlePlayerResponsesFlagThePhoto() throws Exception {
    when(playerService.hasPhoto(any())).thenReturn(true);

    mockMvc
        .perform(asViewer(get("/squad/players/p-1")))
        .andExpect(jsonPath("$.hasPhoto").value(true));
    mockMvc
        .perform(asEditor(put("/squad/players/p-1"), updateBody()))
        .andExpect(jsonPath("$.hasPhoto").value(true));
    mockMvc
        .perform(asEditor(post("/squad/players/p-1/release"), "{\"version\": 3}"))
        .andExpect(jsonPath("$.hasPhoto").value(true));
    mockMvc
        .perform(asEditor(post("/squad/players/p-1/reactivate"), "{\"version\": 3}"))
        .andExpect(jsonPath("$.hasPhoto").value(true));
  }

  /** A player who has just been created can't have a photo: no lookup at all. */
  @Test
  void aCreatedPlayerHasNoPhotoWithoutALookup() throws Exception {
    mockMvc
        .perform(asEditor(post("/squad/players"), createBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.hasPhoto").value(false));

    verify(playerService, never()).hasPhoto(any());
    verify(playerService, never()).playerIdsWithPhoto();
  }

  // --- media types (KAN-31) ----------------------------------------------------------------------

  /**
   * A 500 before KAN-31. Names the types the endpoint reads, in {@code details} and the {@code
   * Accept} header, but never echoes the type that was sent.
   */
  @Test
  void aNonJsonBodyIs415NamingTheAcceptedTypesWithoutEchoingTheSentOne() throws Exception {
    mockMvc
        .perform(
            post("/squad/players")
                .header("Authorization", tokens.bearer("club-a", PermissionLevel.EDIT_FULL))
                .contentType("text/x-marker-12345")
                .content(createBody()))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(header().string("Accept", containsString("application/json")))
        .andExpect(jsonPath("$.status").value(415))
        .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
        .andExpect(jsonPath("$.message").value("Unsupported Content-Type"))
        .andExpect(jsonPath("$.details").value(hasItem("application/json")))
        .andExpect(content().string(not(containsString("x-marker-12345"))));

    verifyNoInteractions(playerService);
  }

  @Test
  void anUnparseableContentTypeIs415() throws Exception {
    mockMvc
        .perform(
            post("/squad/players")
                .header("Authorization", tokens.bearer("club-a", PermissionLevel.EDIT_FULL))
                .header("Content-Type", "marker-12345")
                .content(createBody()))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.message").value("Unsupported Content-Type"))
        .andExpect(content().string(not(containsString("marker-12345"))));
  }

  /** Authentication still comes first: without a token, the content type is never looked at. */
  @Test
  void aNonJsonBodyWithoutATokenIsStill401() throws Exception {
    mockMvc
        .perform(post("/squad/players").contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.message").value("Authentication required"));
  }

  /**
   * The body's type is checked while the handler's arguments are resolved, before {@code
   * PreAuthorize} runs, so a caller who may not create players gets 415 rather than 403 — the same
   * ordering as an invalid body's 400. Nothing is created or revealed either way.
   */
  @Test
  void aNonJsonBodyFromAViewerIs415BeforeTheAuthorityCheck() throws Exception {
    mockMvc
        .perform(
            asViewer(post("/squad/players")).contentType(MediaType.TEXT_PLAIN).content("hello"))
        .andExpect(status().isUnsupportedMediaType());

    verifyNoInteractions(playerService);
  }

  /**
   * A 406 had an empty body before KAN-31: the JSON error itself failed the same content
   * negotiation. Now it's JSON whatever the {@code Accept} header says.
   */
  @Test
  void anAcceptHeaderWithoutJsonIs406WithAJsonBody() throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/players")).accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotAcceptable())
        .andExpect(header().string("Content-Type", "application/json"))
        .andExpect(jsonPath("$.status").value(406))
        .andExpect(jsonPath("$.error").value("Not Acceptable"))
        .andExpect(jsonPath("$.message").value("None of the accepted media types can be produced"))
        .andExpect(jsonPath("$.details").value(hasItem("application/json")));
  }

  /** A 500 with an empty body before KAN-31. */
  @Test
  void anUnparseableAcceptHeaderIs406() throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/players")).header("Accept", "marker-12345"))
        .andExpect(status().isNotAcceptable())
        .andExpect(jsonPath("$.error").value("Not Acceptable"))
        .andExpect(content().string(not(containsString("marker-12345"))));
  }

  /**
   * The photo's {@code Content-Type} is set by the controller, so Spring doesn't negotiate it: the
   * image is served even to a client asking only for JSON.
   */
  @Test
  void thePhotoIsServedEvenToAClientAcceptingOnlyJson() throws Exception {
    mockMvc
        .perform(asViewer(get("/squad/players/p-1/photo")).accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Type", "image/png"))
        .andExpect(content().bytes(TestImages.png()));
  }

  private static Stream<Arguments> commonInvalidFields() {
    return Stream.of(
        Arguments.of("fullName", null),
        Arguments.of("fullName", "   "),
        Arguments.of("fullName", " " + "a".repeat(101) + " "),
        Arguments.of("primaryPosition", null),
        Arguments.of("secondaryPosition", "ST"),
        Arguments.of("jerseyNumber", 0),
        Arguments.of("jerseyNumber", 100),
        Arguments.of("dateOfBirth", null),
        Arguments.of("dateOfBirth", LocalDate.now().minusYears(17).toString()),
        Arguments.of("dateOfBirth", LocalDate.now().minusYears(100).toString()),
        Arguments.of("dateOfBirth", "20/05/1995"),
        Arguments.of("heightCm", 139),
        Arguments.of("heightCm", 221),
        Arguments.of("weightKg", 39),
        Arguments.of("weightKg", 151),
        Arguments.of("preferredFoot", "NONE"),
        Arguments.of("medicalStatus", "HURT"));
  }

  /**
   * A value that can't even be bound (unknown enum, unparseable date) is a malformed body; any
   * other invalid value is a field error whose detail names the field.
   */
  private static void expectFieldError(ResultActions result, String field, Object value)
      throws Exception {
    result.andExpect(status().isBadRequest());
    if (isUnbindable(field, value)) {
      result
          .andExpect(jsonPath("$.error").value("Bad Request"))
          .andExpect(jsonPath("$.message").value("Malformed request body"))
          .andExpect(jsonPath("$.details").value(contains(field + ": invalid value")))
          .andExpect(content().string(not(containsString(String.valueOf(value)))));
    } else {
      result
          .andExpect(jsonPath("$.error").value("Validation Failed"))
          .andExpect(jsonPath("$.details").value(hasItem(startsWith(field + ": "))));
    }
  }

  private static boolean isUnbindable(String field, Object value) {
    if (!(value instanceof String text)) {
      return false;
    }
    return switch (field) {
      case "primaryPosition", "secondaryPosition" -> !isEnumConstant(Position.class, text);
      case "preferredFoot" -> !isEnumConstant(PreferredFoot.class, text);
      case "medicalStatus" -> !isEnumConstant(MedicalStatus.class, text);
      case "dateOfBirth" -> !text.matches("\\d{4}-\\d{2}-\\d{2}");
      default -> false;
    };
  }

  private static <E extends Enum<E>> boolean isEnumConstant(Class<E> type, String name) {
    return Stream.of(type.getEnumConstants()).anyMatch(constant -> constant.name().equals(name));
  }

  private static void assertRequest(CreatePlayerRequest request, String expectedName) {
    assertThat(request.fullName()).isEqualTo(expectedName);
    assertThat(request.medicalStatus()).isNull();
    assertThat(request.primaryPosition()).isEqualTo(Position.ST);
  }

  private MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.VIEW_ONLY));
  }

  private <B extends AbstractMockHttpServletRequestBuilder<B>> B asPhotoEditor(B request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.EDIT_FULL));
  }

  private static MockMultipartHttpServletRequestBuilder photoUpload(String path, byte[] content) {
    return multipart(HttpMethod.PUT, path)
        .file(new MockMultipartFile("file", "photo", "application/octet-stream", content));
  }

  private static byte[] jpegOfSize(int size) {
    return TestImages.jpegOfSize(size);
  }

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request.header("Authorization", tokens.bearer("club-a", PermissionLevel.ADMIN));
  }

  private MockHttpServletRequestBuilder asEditor(
      MockHttpServletRequestBuilder request, String body) {
    return request
        .header("Authorization", tokens.bearer("club-a", PermissionLevel.EDIT_FULL))
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private static Map<String, Object> createFields() {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("fullName", "Eran Zahavi");
    body.put("primaryPosition", "ST");
    body.put("secondaryPosition", "AM");
    body.put("jerseyNumber", 7);
    body.put("dateOfBirth", "1995-05-20");
    body.put("heightCm", 180);
    body.put("weightKg", 75);
    body.put("preferredFoot", "RIGHT");
    body.put("medicalStatus", "FIT");
    return body;
  }

  private static Map<String, Object> updateFields() {
    Map<String, Object> body = createFields();
    body.put("medicalStatus", "INJURED");
    body.put("version", 3);
    return body;
  }

  private static String postBody(String path) {
    if (path.endsWith("/release") || path.endsWith("/reactivate")) {
      return "{\"version\": 3}";
    }
    return createBody();
  }

  private static String createBody() {
    return json(createFields());
  }

  private static String updateBody() {
    return json(updateFields());
  }

  private static String json(Map<String, Object> body) {
    return JSON.writeValueAsString(body);
  }

  private static Player player() {
    Player player = new Player();
    player.setId("p-1");
    player.setClubId("club-a");
    player.setFullName("Eran Zahavi");
    player.setPrimaryPosition(Position.ST);
    player.setSecondaryPosition(Position.AM);
    player.setJerseyNumber(7);
    player.setDateOfBirth(LocalDate.of(1995, 5, 20));
    player.setHeightCm(180);
    player.setWeightKg(75);
    player.setPreferredFoot(PreferredFoot.RIGHT);
    return player;
  }
}
