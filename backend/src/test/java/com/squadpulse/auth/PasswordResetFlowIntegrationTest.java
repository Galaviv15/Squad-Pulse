package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.redis.testcontainers.RedisContainer;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.EmailSender;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Forgot / reset password and invite activation end to end — security chain, controllers, the
 * club-scoped repositories on a real MongoDB, codes and refresh-token families on a real Redis.
 * {@link EmailSender} is a mock, so tests read the codes that were "sent" from its invocations.
 *
 * <p>As in {@link AuthFlowIntegrationTest}, {@link #perform} asserts that no {@link ClubContext} is
 * left behind on the test thread after each request.
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@AutoConfigureMockMvc
@Testcontainers
class PasswordResetFlowIntegrationTest {

  private static final String PASSWORD = "correct-horse-battery";
  private static final String NEW_PASSWORD = "a-brand-new-passphrase";
  private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @Container
  static final RedisContainer REDIS_CONTAINER =
      new RedisContainer(DockerImageName.parse("redis:7-alpine"));

  @DynamicPropertySource
  static void containerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
    registry.add("spring.data.redis.host", REDIS_CONTAINER::getRedisHost);
    registry.add("spring.data.redis.port", REDIS_CONTAINER::getRedisPort);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;
  @Autowired private StringRedisTemplate redis;
  @Autowired private PasswordEncoder passwordEncoder;
  @Autowired private ClubContext clubContext;
  @MockitoBean private EmailSender emailSender;

  @AfterEach
  void tearDown() {
    clubContext.clear();
    mongoTemplate.remove(new Query(), User.class);
    redis.getConnectionFactory().getConnection().serverCommands().flushAll();
  }

  // --- forgot-password ---------------------------------------------------------------------------

  /** Registered, unknown, deactivated or throttled: the response is byte-for-byte the same. */
  @Test
  void forgotPasswordLooksTheSameWhateverTheEmail() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    insertUser("club-a", "former@example.com", false);
    insertUser("club-a", "busy@example.com", true);
    for (int i = 0; i < 5; i++) {
      forgotPassword("busy@example.com");
    }
    clearInvocations(emailSender);

    List<MvcResult> results =
        List.of(
            forgotPassword("coach@example.com"),
            forgotPassword("nobody@example.com"),
            forgotPassword("former@example.com"),
            forgotPassword("busy@example.com"));

    for (MvcResult result : results) {
      assertThat(result.getResponse().getStatus()).isEqualTo(202);
      assertThat(result.getResponse().getContentAsString()).isEmpty();
      assertThat(result.getResponse().getHeaderNames())
          .isEqualTo(results.get(0).getResponse().getHeaderNames());
    }
    // Only the active, registered, unthrottled user got an email.
    verify(emailSender).send(eq("coach@example.com"), anyString(), anyString());
    verify(emailSender, times(1)).send(anyString(), anyString(), anyString());
  }

  @Test
  void theSixthRequestInTheWindowSendsNothingButIsStill202() throws Exception {
    insertUser("club-a", "coach@example.com", true);

    for (int i = 0; i < 5; i++) {
      forgotPassword("coach@example.com");
    }
    verify(emailSender, times(5)).send(eq("coach@example.com"), anyString(), anyString());
    String fifthCode = lastCodeSentTo("coach@example.com");

    MvcResult sixth = forgotPassword("Coach@Example.com");

    assertThat(sixth.getResponse().getStatus()).isEqualTo(202);
    verify(emailSender, times(5)).send(anyString(), anyString(), anyString());
    // Nothing was issued either: the fifth code is still the current one.
    resetPassword("coach@example.com", fifthCode, NEW_PASSWORD).andExpect(status().isNoContent());
  }

  @Test
  void unknownEmailsAreCountedToo() throws Exception {
    for (int i = 0; i < 6; i++) {
      forgotPassword("nobody@example.com");
    }

    assertThat(
            redis
                .opsForValue()
                .get(PasswordResetCodeService.REQUEST_KEY_PREFIX + "nobody@example.com"))
        .isEqualTo("6");
    verify(emailSender, never()).send(anyString(), anyString(), anyString());
  }

  // --- reset-password ----------------------------------------------------------------------------

  @Test
  void aResetSetsTheNewPasswordAndTheOldOneStopsWorking() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    forgotPassword("coach@example.com");

    resetPassword("coach@example.com", lastCodeSentTo("coach@example.com"), NEW_PASSWORD)
        .andExpect(status().isNoContent());

    login("coach@example.com", PASSWORD).andExpect(status().isUnauthorized());
    login("coach@example.com", NEW_PASSWORD).andExpect(status().isOk());
  }

  /**
   * The save runs in the found user's club — the endpoint is public, so there's no club context to
   * begin with — and touches nothing in any other club.
   */
  @Test
  void theSaveLandsOnTheRightUserInTheRightClub() throws Exception {
    User clubA = insertUser("club-a", "coach@club-a.example.com", true);
    User clubB = insertUser("club-b", "coach@club-b.example.com", true);
    User clubABefore = mongoTemplate.findById(clubA.getId(), User.class);
    forgotPassword("coach@club-b.example.com");

    resetPassword(
            "coach@club-b.example.com", lastCodeSentTo("coach@club-b.example.com"), NEW_PASSWORD)
        .andExpect(status().isNoContent());

    User stored = mongoTemplate.findById(clubB.getId(), User.class);
    assertThat(stored.getClubId()).isEqualTo("club-b");
    assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isTrue();
    assertThat(stored.getSessionsInvalidatedAt()).isNotNull();
    assertThat(mongoTemplate.count(new Query(), User.class)).isEqualTo(2);
    assertThat(mongoTemplate.findById(clubA.getId(), User.class))
        .usingRecursiveComparison()
        .isEqualTo(clubABefore);
  }

  /**
   * A stray access token from another club on the (public) request sets that club's context — the
   * save must still land in the reset user's own club, and the context be cleaned up.
   */
  @Test
  void anAccessTokenFromAnotherClubOnTheRequestDoesntRedirectTheSave() throws Exception {
    insertUser("club-a", "manager@club-a.example.com", true);
    User clubB = insertUser("club-b", "coach@club-b.example.com", true);
    String clubAToken = accessToken(login("manager@club-a.example.com", PASSWORD).andReturn());
    forgotPassword("coach@club-b.example.com");

    perform(
            resetPasswordRequest(
                    "coach@club-b.example.com",
                    lastCodeSentTo("coach@club-b.example.com"),
                    NEW_PASSWORD)
                .header("Authorization", "Bearer " + clubAToken))
        .andExpect(status().isNoContent());

    User stored = mongoTemplate.findById(clubB.getId(), User.class);
    assertThat(stored.getClubId()).isEqualTo("club-b");
    assertThat(passwordEncoder.matches(NEW_PASSWORD, stored.getPasswordHash())).isTrue();
  }

  @Test
  void aCodeWorksOnlyOnce() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    forgotPassword("coach@example.com");
    String code = lastCodeSentTo("coach@example.com");

    resetPassword("coach@example.com", code, NEW_PASSWORD).andExpect(status().isNoContent());

    assertGenericResetFailure(resetPassword("coach@example.com", code, "yet-another-password"));
    login("coach@example.com", NEW_PASSWORD).andExpect(status().isOk());
  }

  @Test
  void everyResetFailureLooksTheSame() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    insertUser("club-a", "expired@example.com", true);
    insertUser("club-a", "former@example.com", true);
    forgotPassword("coach@example.com");
    forgotPassword("expired@example.com");
    forgotPassword("former@example.com");
    String code = lastCodeSentTo("coach@example.com");
    // Expire one code...
    String expiredKey = PasswordResetCodeService.CODE_KEY_PREFIX + "expired@example.com";
    redis.expire(expiredKey, Duration.ofMillis(1));
    await().atMost(Duration.ofSeconds(5)).until(() -> !redis.hasKey(expiredKey));
    // ...and deactivate a user after their code was sent.
    mongoTemplate.updateFirst(
        Query.query(Criteria.where("email").is("former@example.com")),
        Update.update("active", false),
        User.class);

    Map<String, Object> wrongCode =
        assertGenericResetFailure(
            resetPassword(
                "coach@example.com", code.equals("000000") ? "000001" : "000000", NEW_PASSWORD));
    Map<String, Object> noCodeRequested =
        assertGenericResetFailure(resetPassword("nobody@example.com", code, NEW_PASSWORD));
    Map<String, Object> expired =
        assertGenericResetFailure(
            resetPassword(
                "expired@example.com", lastCodeSentTo("expired@example.com"), NEW_PASSWORD));
    Map<String, Object> deactivated =
        assertGenericResetFailure(
            resetPassword(
                "former@example.com", lastCodeSentTo("former@example.com"), NEW_PASSWORD));

    assertThat(wrongCode).isEqualTo(noCodeRequested).isEqualTo(expired).isEqualTo(deactivated);
    User former =
        mongoTemplate.findOne(
            Query.query(Criteria.where("email").is("former@example.com")), User.class);
    assertThat(passwordEncoder.matches(PASSWORD, former.getPasswordHash())).isTrue();
  }

  @Test
  void fiveWrongGuessesBurnTheCode() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    forgotPassword("coach@example.com");
    String code = lastCodeSentTo("coach@example.com");
    String wrong = code.equals("000000") ? "000001" : "000000";

    for (int i = 0; i < 5; i++) {
      assertGenericResetFailure(resetPassword("coach@example.com", wrong, NEW_PASSWORD));
    }

    assertGenericResetFailure(resetPassword("coach@example.com", code, NEW_PASSWORD));
  }

  /** Bean validation runs first, so a malformed request costs neither the code nor a guess. */
  @Test
  void aValidationFailureDoesntConsumeTheCodeOrAnAttempt() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    forgotPassword("coach@example.com");
    String code = lastCodeSentTo("coach@example.com");

    resetPassword("coach@example.com", code, "short").andExpect(status().isBadRequest());
    resetPassword("coach@example.com", "12345", NEW_PASSWORD).andExpect(status().isBadRequest());
    resetPassword("coach@example.com", "12345x", NEW_PASSWORD).andExpect(status().isBadRequest());

    assertThat(
            redis
                .opsForHash()
                .get(
                    PasswordResetCodeService.CODE_KEY_PREFIX + "coach@example.com",
                    PasswordResetCodeService.ATTEMPTS_FIELD))
        .isEqualTo("0");
    resetPassword("coach@example.com", code, NEW_PASSWORD).andExpect(status().isNoContent());
  }

  // --- session revocation ------------------------------------------------------------------------

  @Test
  void aResetEndsEveryRefreshSessionButALaterLoginWorks() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    Cookie laptop = refreshCookie(login("coach@example.com", PASSWORD).andReturn());
    Cookie phone = refreshCookie(login("coach@example.com", PASSWORD).andReturn());
    forgotPassword("coach@example.com");

    resetPassword("coach@example.com", lastCodeSentTo("coach@example.com"), NEW_PASSWORD)
        .andExpect(status().isNoContent());

    perform(post("/auth/refresh").cookie(laptop)).andExpect(status().isUnauthorized());
    perform(post("/auth/refresh").cookie(phone)).andExpect(status().isUnauthorized());

    Cookie afterReset = refreshCookie(login("coach@example.com", NEW_PASSWORD).andReturn());
    MvcResult refreshed = perform(post("/auth/refresh").cookie(afterReset)).andReturn();
    assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);
    perform(post("/auth/refresh").cookie(refreshCookie(refreshed))).andExpect(status().isOk());
  }

  /** A family from before issuedAt was recorded is treated as the oldest possible one. */
  @Test
  void aLegacyFamilyWithoutAnIssueTimeIsEndedByAReset() throws Exception {
    insertUser("club-a", "coach@example.com", true);
    Cookie legacy = refreshCookie(login("coach@example.com", PASSWORD).andReturn());
    redis
        .keys(RefreshTokenService.FAMILY_KEY_PREFIX + "*")
        .forEach(key -> redis.opsForHash().delete(key, RefreshTokenService.ISSUED_AT_FIELD));
    // Still fine while the user's sessions were never invalidated.
    MvcResult refreshed = perform(post("/auth/refresh").cookie(legacy)).andReturn();
    assertThat(refreshed.getResponse().getStatus()).isEqualTo(200);

    mongoTemplate.updateFirst(
        Query.query(Criteria.where("email").is("coach@example.com")),
        Update.update("sessionsInvalidatedAt", Instant.now()),
        User.class);

    perform(post("/auth/refresh").cookie(refreshCookie(refreshed)))
        .andExpect(status().isUnauthorized());
  }

  // --- helpers -----------------------------------------------------------------------------------

  /** Performs the request and checks that it left no clubId behind on this thread. */
  private ResultActions perform(RequestBuilder request) throws Exception {
    ResultActions result = mockMvc.perform(request);
    assertThat(clubContext.getClubId()).as("ClubContext after the request").isEmpty();
    return result;
  }

  private MvcResult forgotPassword(String email) throws Exception {
    return perform(
            post("/auth/forgot-password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\"}".formatted(email)))
        .andExpect(status().isAccepted())
        .andReturn();
  }

  private ResultActions resetPassword(String email, String code, String newPassword)
      throws Exception {
    return perform(resetPasswordRequest(email, code, newPassword));
  }

  private static MockHttpServletRequestBuilder resetPasswordRequest(
      String email, String code, String newPassword) {
    return post("/auth/reset-password")
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"email\":\"%s\",\"code\":\"%s\",\"newPassword\":\"%s\"}"
                .formatted(email, code, newPassword));
  }

  /** Asserts the generic 401 and returns its body without {@code timestamp}, for comparing. */
  private static Map<String, Object> assertGenericResetFailure(ResultActions result)
      throws Exception {
    String body =
        result
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Invalid or expired code"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Map<String, Object> fields = new HashMap<>(JsonPath.<Map<String, Object>>read(body, "$"));
    fields.remove("timestamp");
    return fields;
  }

  private ResultActions login(String email, String password) throws Exception {
    return perform(
        post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
  }

  /** The code in the most recent email sent to {@code email}. */
  private String lastCodeSentTo(String email) {
    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(emailSender, atLeastOnce()).send(eq(email), anyString(), body.capture());
    Matcher matcher = CODE.matcher(body.getValue());
    assertThat(matcher.find()).as("a 6-digit code in the email to %s", email).isTrue();
    return matcher.group(1);
  }

  private User insertUser(String clubId, String email, boolean active) {
    User user = new User();
    user.setClubId(clubId);
    user.setEmail(email);
    user.setPasswordHash(passwordEncoder.encode(PASSWORD));
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(PermissionLevel.EDIT_FULL);
    user.setFullName("Dana Levi");
    user.setActive(active);
    return mongoTemplate.insert(user);
  }

  private static Cookie refreshCookie(MvcResult result) {
    Cookie cookie = result.getResponse().getCookie(AuthController.REFRESH_COOKIE);
    assertThat(cookie).as("refresh cookie").isNotNull();
    return new Cookie(cookie.getName(), cookie.getValue());
  }

  private static String accessToken(MvcResult result) throws Exception {
    assertThat(result.getResponse().getStatus()).isEqualTo(200);
    return JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
  }
}
