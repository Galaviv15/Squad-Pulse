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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.squadpulse.auth.AuthWebMvcTestConfig;
import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import com.squadpulse.common.NotFoundException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTP contract of {@link PlayerController} behind the real security chain, with {@link
 * PlayerService} mocked: who may call what, request validation (body and query string), the 201 /
 * 404 / 409 mappings, and the response shape. That the service really stays within the caller's
 * club, and what it writes, is proven on a real MongoDB in {@link PlayerApiIntegrationTest}.
 */
@WebMvcTest(
    controllers = PlayerController.class,
    excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
    properties = {AuthWebMvcTestConfig.JWT_SECRET, AuthWebMvcTestConfig.PASSWORD_PEPPER})
@Import({AuthWebMvcTestConfig.class, TestAccessTokens.class})
class PlayerControllerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Autowired private MockMvc mockMvc;
  @Autowired private TestAccessTokens tokens;
  @MockitoBean private PlayerService playerService;

  @BeforeEach
  void stubService() {
    when(playerService.list(any())).thenReturn(List.of(player()));
    when(playerService.get(anyString())).thenReturn(player());
    when(playerService.create(any())).thenReturn(player());
    when(playerService.update(anyString(), any())).thenReturn(player());
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
  })
  void eachEndpointAdmitsExactlyTheLevelsAtOrAboveItsMinimum(
      String method, String path, String caller, int expectedStatus) throws Exception {
    MockHttpServletRequestBuilder request =
        switch (method) {
          case "GET" -> get(path);
          case "POST" -> post(path).contentType(MediaType.APPLICATION_JSON).content(createBody());
          case "PUT" -> put(path).contentType(MediaType.APPLICATION_JSON).content(updateBody());
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

  // --- helpers -----------------------------------------------------------------------------------

  /** Field rules shared by create and update: {field, invalid value}. */
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
