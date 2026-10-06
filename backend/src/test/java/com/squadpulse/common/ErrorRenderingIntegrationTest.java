package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.squadpulse.auth.PermissionLevel;
import com.squadpulse.auth.TestAccessTokens;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.catalina.Context;
import org.apache.catalina.connector.ClientAbortException;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.filter.OncePerRequestFilter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Errors raised below Spring MVC (KAN-35), on a real embedded Tomcat — MockMvc performs neither a
 * real filter chain's exception propagation nor the container's error-page dispatch to {@code
 * /error}. Every one must be answered in the {@link ApiErrorResponse} shape, as JSON whatever the
 * {@code Accept} header, without echoing the request, and logged as {@link GlobalExceptionHandler}
 * logs: a 5xx once at ERROR with the method and path, a 4xx never at ERROR.
 *
 * <p>Failures are triggered by {@link FailingFilter}, a test-only filter registered (by {@link
 * FailingFilterConfig}, in this context only) right after Spring Security's chain — where a real
 * filter failure would happen — so its requests carry a valid access token. Logs are captured on
 * the root logger (by {@link CapturedLogs}), which also receives Tomcat's own (bridged from JUL),
 * so "exactly one ERROR" covers the container's logger too.
 */
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
@Import({TestAccessTokens.class, ErrorRenderingIntegrationTest.FailingFilterConfig.class})
class ErrorRenderingIntegrationTest {

  private static final String SECRET = "secret-db-password";
  private static final String FAILING_PATH = "/test-only/filter-failure/";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @LocalServerPort private int port;
  @Autowired private TestAccessTokens tokens;
  @Autowired private ApplicationContext context;

  private final HttpClient client =
      HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
  private final Logger filterLogger =
      (Logger) LoggerFactory.getLogger(UnhandledExceptionFilter.class);
  private final CapturedLogs logs = new CapturedLogs();
  private Level filterLoggerLevel;

  @BeforeEach
  void captureLogs() {
    logs.start();
    filterLoggerLevel = filterLogger.getLevel();
  }

  @AfterEach
  void stopCapturing() {
    logs.stop();
    filterLogger.setLevel(filterLoggerLevel);
  }

  /**
   * The exception never leaves our filter, so Tomcat neither logs it nor renders {@code /error}.
   */
  @ParameterizedTest
  @ValueSource(strings = {"application/json", "text/html", "application/xml"})
  void aFilterExceptionIsAJson500LoggedOnceByUs(String accept) throws Exception {
    HttpResponse<String> response = get(FAILING_PATH + "throw", accept, true);

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    assertApiError(
        response, 500, "Internal Server Error", GlobalExceptionHandler.UNEXPECTED_ERROR_MESSAGE);
    assertThat(response.body()).doesNotContain(SECRET).doesNotContain("test-only");

    ILoggingEvent error = logs.theOnlyError();
    assertThat(error.getLoggerName()).isEqualTo(UnhandledExceptionFilter.class.getName());
    assertThat(error.getFormattedMessage())
        .isEqualTo("Unexpected error handling GET " + FAILING_PATH + "throw");
    assertThat(ThrowableProxyUtil.asString(error.getThrowableProxy()))
        .contains("java.lang.IllegalStateException: " + SECRET)
        .contains("at " + FailingFilter.class.getName());
  }

  /** Paths Tomcat passes on, but Spring Security's {@code StrictHttpFirewall} rejects. */
  static Stream<Arguments> firewallRejections() {
    List<Arguments> arguments = new ArrayList<>();
    for (String path : List.of("/squad//players", "/squad;jsessionid=abc/players")) {
      for (String accept : List.of("application/json", "text/html")) {
        arguments.add(Arguments.of(path, accept));
      }
    }
    return arguments.stream();
  }

  @ParameterizedTest
  @MethodSource("firewallRejections")
  void aFirewallRejectionIsAJson400ThatIsNotLoggedAtError(String path, String accept)
      throws Exception {
    HttpResponse<String> response = get(path, accept, false);

    assertThat(response.statusCode()).isEqualTo(400);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    assertApiError(response, 400, "Bad Request", GlobalExceptionHandler.CLIENT_ERROR_MESSAGE);
    assertThat(response.body()).doesNotContain("squad").doesNotContain("jsessionid");
    logs.assertNoErrors();
  }

  @Test
  void aBareSendErrorKeepsItsStatusAndIsLoggedAtErrorWithoutAStackTrace() throws Exception {
    HttpResponse<String> response = get(FAILING_PATH + "send-503", "text/html", true);

    assertThat(response.statusCode()).isEqualTo(503);
    assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
    assertApiError(
        response, 503, "Service Unavailable", GlobalExceptionHandler.UNEXPECTED_ERROR_MESSAGE);

    ILoggingEvent error = logs.theOnlyError();
    assertThat(error.getLoggerName()).isEqualTo(ApiErrorController.class.getName());
    assertThat(error.getFormattedMessage())
        .isEqualTo("Answered GET " + FAILING_PATH + "send-503 with 503");
    assertThat(error.getThrowableProxy()).isNull();
  }

  /** A direct request is a {@code REQUEST} dispatch: the security chain still requires a token. */
  @Test
  void aDirectRequestForErrorWithoutATokenIsTheSecurityChains401() throws Exception {
    HttpResponse<String> response = get("/error", "application/json", false);

    assertThat(response.statusCode()).isEqualTo(401);
    assertApiError(response, 401, "Unauthorized", "Authentication required");
    logs.assertNoErrors();
  }

  /** ... and with one, it's a 404 like any unknown path, never a made-up 500. */
  @Test
  void aDirectRequestForErrorWithATokenIsA404() throws Exception {
    HttpResponse<String> response = get("/error", "application/json", true);

    assertThat(response.statusCode()).isEqualTo(404);
    assertApiError(response, 404, "Not Found", "No endpoint GET /error");
    logs.assertNoErrors();
  }

  /** A failure halfway through a response: logged once, but nothing appended to what was sent. */
  @Test
  void aFilterExceptionAfterTheResponseWasCommittedIsLoggedOnceAndAppendsNothing()
      throws Exception {
    HttpResponse<String> response = get(FAILING_PATH + "committed", "application/json", true);

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("partial");
    ILoggingEvent error = logs.theOnlyError();
    assertThat(error.getLoggerName()).isEqualTo(UnhandledExceptionFilter.class.getName());
    assertThat(error.getFormattedMessage())
        .isEqualTo("Unexpected error handling GET " + FAILING_PATH + "committed");
  }

  @Test
  void aClientThatWentAwayIsLoggedAtDebugOnly() throws Exception {
    filterLogger.setLevel(Level.DEBUG);

    get(FAILING_PATH + "disconnect", "application/json", true);

    logs.assertNoWarningsOrAbove();
    assertThat(logs.events())
        .filteredOn(event -> event.getLoggerName().equals(UnhandledExceptionFilter.class.getName()))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
              assertThat(event.getFormattedMessage())
                  .startsWith("Client disconnected during GET " + FAILING_PATH + "disconnect");
            });
  }

  /** Boot's {@code BasicErrorController} backs off for ours, and nothing else is one. */
  @Test
  void apiErrorControllerIsTheOnlyErrorController() {
    assertThat(context.getBeansOfType(ErrorController.class).values())
        .singleElement()
        .isInstanceOf(ApiErrorController.class);
    assertThat(context.containsBean("basicErrorController")).isFalse();
  }

  /**
   * The order the running Tomcat actually applies its filters in: ours wraps Spring Security's
   * chain and everything after it; only Boot's character-encoding filter comes first.
   */
  @Test
  void theUnhandledExceptionFilterRunsBeforeEveryOtherFilterButCharacterEncoding() {
    TomcatWebServer webServer =
        (TomcatWebServer) ((WebServerApplicationContext) context).getWebServer();
    Context tomcatContext = (Context) webServer.getTomcat().getHost().findChildren()[0];
    List<String> filterOrder =
        Arrays.stream(tomcatContext.findFilterMaps()).map(FilterMap::getFilterName).toList();

    assertThat(filterOrder)
        .startsWith("characterEncodingFilter", UnhandledExceptionFilterConfig.FILTER_NAME)
        .contains("springSecurityFilterChain", "failingFilter")
        .containsSubsequence(
            UnhandledExceptionFilterConfig.FILTER_NAME,
            "springSecurityFilterChain",
            "failingFilter");
  }

  private HttpResponse<String> get(String path, String accept, boolean withToken) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Accept", accept);
    if (withToken) {
      request.header("Authorization", tokens.bearer("club-a", PermissionLevel.ADMIN));
    }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
  }

  /**
   * The exact {@link ApiErrorResponse} shape: these six fields, no others, as the real JSON mapper
   * writes them — {@code code} included, as an explicit {@code null}.
   */
  private static void assertApiError(
      HttpResponse<String> response, int status, String error, String message) {
    JsonNode body = JSON.readTree(response.body());
    List<String> fields = new ArrayList<>();
    Iterator<String> names = body.propertyNames().iterator();
    names.forEachRemaining(fields::add);
    assertThat(fields)
        .containsExactly("timestamp", "status", "error", "code", "message", "details");
    assertThat(body.get("code").isNull()).isTrue();
    assertThat(body.get("status").asInt()).isEqualTo(status);
    assertThat(body.get("error").asString()).isEqualTo(error);
    assertThat(body.get("message").asString()).isEqualTo(message);
    assertThat(body.get("details").isArray()).isTrue();
    assertThat(body.get("details").isEmpty()).isTrue();
  }

  /** Test-only: acts on {@link #FAILING_PATH} alone, passes everything else through. */
  static class FailingFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
      return !request.getRequestURI().startsWith(FAILING_PATH);
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
      switch (request.getRequestURI().substring(FAILING_PATH.length())) {
        case "throw" -> throw new IllegalStateException(SECRET);
        case "send-503" -> response.sendError(503);
        case "committed" -> {
          response.getOutputStream().write("partial".getBytes(StandardCharsets.US_ASCII));
          response.flushBuffer();
          throw new IllegalStateException(SECRET);
        }
        case "disconnect" -> throw new ClientAbortException(new IOException("Broken pipe"));
        default -> chain.doFilter(request, response);
      }
    }
  }

  /** Imported by this test only; as a {@code @TestConfiguration} it's never component-scanned. */
  @TestConfiguration
  static class FailingFilterConfig {

    @Bean
    FilterRegistrationBean<FailingFilter> failingFilter() {
      FilterRegistrationBean<FailingFilter> registration =
          new FilterRegistrationBean<>(new FailingFilter());
      registration.setName("failingFilter");
      registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 1);
      return registration;
    }
  }
}
