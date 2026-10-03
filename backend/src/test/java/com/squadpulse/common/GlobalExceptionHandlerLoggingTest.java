package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * What {@link GlobalExceptionHandler} logs (KAN-31), and its catch-all end to end, through Spring
 * MVC's real exception resolution: every 500 at ERROR with its stack trace, method and path, but
 * nothing the client sent; a client error never at ERROR; a client that went away not at all.
 *
 * <p>Standalone MockMvc with {@link Probe}, which has no stereotype annotation, so component
 * scanning never adds it to another test's application context. No security chain: these are about
 * the advice alone.
 */
class GlobalExceptionHandlerLoggingTest {

  private static final String QUERY_MARKER = "query-marker-345";
  private static final String BODY_MARKER = "secret-marker-678";
  private static final String TOKEN_MARKER = "token-marker-901";

  private final MockMvc mockMvc =
      MockMvcBuilders.standaloneSetup(new Probe())
          .setControllerAdvice(new GlobalExceptionHandler())
          .setCustomHandlerMapping(
              () ->
                  new RequestMappingHandlerMapping() {
                    @Override
                    protected boolean isHandler(Class<?> beanType) {
                      return beanType == Probe.class;
                    }
                  })
          .build();

  private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    appender.start();
    rootLogger.addAppender(appender);
  }

  @AfterEach
  void stopCapturing() {
    rootLogger.detachAppender(appender);
  }

  @Test
  void anUnexpectedExceptionIsLoggedOnceAtErrorWithItsStackTraceMethodAndPath() throws Exception {
    mockMvc
        .perform(get("/probe/bug"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.error").value("Internal Server Error"))
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
        .andExpect(jsonPath("$.details").isEmpty());

    ILoggingEvent error = theOnlyError();
    assertThat(error.getLoggerName()).isEqualTo(GlobalExceptionHandler.class.getName());
    assertThat(error.getFormattedMessage()).isEqualTo("Unexpected error handling GET /probe/bug");
    assertThat(ThrowableProxyUtil.asString(error.getThrowableProxy()))
        .contains("java.lang.IllegalStateException: a bug")
        .contains("at com.squadpulse.common.GlobalExceptionHandlerLoggingTest$Probe.bug");
  }

  /** Path only: no query string, no body, no header — the token above all. */
  @Test
  void theLogNeverContainsTheQueryStringBodyOrHeaders() throws Exception {
    mockMvc
        .perform(
            post("/probe/bug-with-body?q=" + QUERY_MARKER)
                .header("Authorization", "Bearer " + TOKEN_MARKER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + BODY_MARKER + "\"}"))
        .andExpect(status().isInternalServerError());

    assertThat(theOnlyError().getFormattedMessage())
        .isEqualTo("Unexpected error handling POST /probe/bug-with-body");
    assertThat(everythingLogged())
        .doesNotContain(QUERY_MARKER)
        .doesNotContain(BODY_MARKER)
        .doesNotContain(TOKEN_MARKER);
  }

  /** Always a server bug, so logged; the response is exactly what it was before KAN-31. */
  @Test
  void aMissingClubContextIsLoggedAtErrorAndItsResponseIsUnchanged() throws Exception {
    mockMvc
        .perform(get("/probe/no-club"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.error").value("Internal Server Error"))
        .andExpect(jsonPath("$.message").value(new MissingClubContextException().getMessage()));

    assertThat(theOnlyError().getFormattedMessage())
        .isEqualTo("Unexpected error handling GET /probe/no-club");
    assertThat(theOnlyError().getThrowableProxy().getClassName())
        .isEqualTo(MissingClubContextException.class.getName());
  }

  /**
   * A failed return-value constraint is the server's fault. Spring MVC itself never validates
   * return values, so this is a direct call rather than a request.
   */
  @Test
  void aFailedReturnValueConstraintIsLoggedAtError() throws Exception {
    Method method = Probe.class.getDeclaredMethod("bug");
    HandlerMethodValidationException ex =
        new HandlerMethodValidationException(
            MethodValidationResult.create(
                new Probe(),
                method,
                List.of(
                    new ParameterValidationResult(
                        new MethodParameter(method, -1),
                        null,
                        List.of(new DefaultMessageSourceResolvable(null, null, "must not be null")),
                        null,
                        null,
                        null,
                        (error, type) -> {
                          throw new IllegalArgumentException();
                        }))));

    ResponseEntity<ApiErrorResponse> response =
        new GlobalExceptionHandler()
            .handleMethodValidation(ex, new MockHttpServletRequest("GET", "/probe/bug"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    assertThat(theOnlyError().getFormattedMessage())
        .isEqualTo("Unexpected error handling GET /probe/bug");
  }

  @Test
  void anUnmappedClientErrorKeepsItsStatusAndIsNotLoggedAtError() throws Exception {
    mockMvc
        .perform(get("/probe/gone-resource"))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.status").value(410))
        .andExpect(jsonPath("$.error").value("Gone"))
        .andExpect(jsonPath("$.message").value("The request could not be processed"))
        .andExpect(content().string(not(containsString("marker"))));

    assertThat(errors()).isEmpty();
  }

  @Test
  void anUnmappedServerErrorKeepsItsStatusAndIsLoggedAtError() throws Exception {
    mockMvc
        .perform(get("/probe/unavailable"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.error").value("Service Unavailable"))
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

    assertThat(theOnlyError().getFormattedMessage())
        .isEqualTo("Unexpected error handling GET /probe/unavailable");
    assertThat(theOnlyError().getThrowableProxy()).isNotNull();
  }

  @Test
  void clientErrorsAreNeverLoggedAtError() throws Exception {
    mockMvc
        .perform(
            post("/probe/bug-with-body").contentType(MediaType.TEXT_PLAIN).content(BODY_MARKER))
        .andExpect(status().isUnsupportedMediaType());
    mockMvc
        .perform(
            post("/probe/bug-with-body")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("Validation Failed"));
    mockMvc
        .perform(get("/probe/record").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isNotAcceptable());

    assertThat(errors()).isEmpty();
  }

  /**
   * A 500 is answered as JSON even to a client that accepts only XML, rather than failing a second
   * time while its body is negotiated.
   */
  @Test
  void aServerErrorIsAnsweredAsJsonWhateverTheAcceptHeader() throws Exception {
    mockMvc
        .perform(get("/probe/bug-for-xml").accept(MediaType.APPLICATION_XML))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.message").value("An unexpected error occurred"));

    assertThat(theOnlyError().getFormattedMessage())
        .isEqualTo("Unexpected error handling GET /probe/bug-for-xml");
  }

  /**
   * Tomcat reports a client that closed the connection as a {@code ClientAbortException} from the
   * response stream. Nothing logged at WARN or above, nothing written.
   */
  @Test
  void aClientThatWentAwayIsNeitherLoggedNorAnswered() throws Exception {
    mockMvc.perform(get("/probe/gone")).andExpect(status().isOk()).andExpect(content().string(""));

    assertThat(appender.list)
        .filteredOn(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .isEmpty();
  }

  /** A failure halfway through a download: logged, but no JSON appended to what was sent. */
  @Test
  void aFailureAfterTheResponseWasCommittedIsLoggedButAppendsNothing() throws Exception {
    mockMvc
        .perform(get("/probe/half-sent"))
        .andExpect(status().isOk())
        .andExpect(content().string("partial"));

    assertThat(theOnlyError().getFormattedMessage())
        .isEqualTo("Unexpected error handling GET /probe/half-sent");
  }

  private List<ILoggingEvent> errors() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
  }

  private ILoggingEvent theOnlyError() {
    assertThat(errors()).hasSize(1);
    return errors().get(0);
  }

  /** Every captured message with its stack trace, as a log file would hold them. */
  private String everythingLogged() {
    StringBuilder all = new StringBuilder();
    for (ILoggingEvent event : appender.list) {
      all.append(event.getFormattedMessage()).append('\n');
      if (event.getThrowableProxy() != null) {
        all.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
      }
    }
    return all.toString();
  }

  record NamedBody(@NotBlank String name) {}

  /**
   * Not a {@code @RestController}, see the class comment; the handler mapping above is told to
   * treat it as a controller instead.
   */
  static class Probe {

    @GetMapping("/probe/bug")
    @ResponseBody
    String bug() {
      throw new IllegalStateException("a bug");
    }

    @PostMapping("/probe/bug-with-body")
    @ResponseBody
    String bugWithBody(@Valid @RequestBody NamedBody body) {
      throw new IllegalStateException("a bug");
    }

    @GetMapping("/probe/bug-for-xml")
    void bugForXml() {
      throw new IllegalStateException("a bug");
    }

    /** Only JSON can represent this, so a request accepting only XML is a 406. */
    @GetMapping("/probe/record")
    @ResponseBody
    NamedBody record() {
      return new NamedBody("name");
    }

    @GetMapping("/probe/no-club")
    @ResponseBody
    String noClub() {
      throw new MissingClubContextException();
    }

    @GetMapping("/probe/gone-resource")
    @ResponseBody
    String goneResource() {
      throw new ResponseStatusException(HttpStatus.GONE, "marker in the reason");
    }

    @GetMapping("/probe/unavailable")
    @ResponseBody
    String unavailable() {
      throw new ErrorResponseException(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @GetMapping("/probe/gone")
    void gone() throws IOException {
      throw new ClientAbortException(new IOException("Broken pipe"));
    }

    @GetMapping("/probe/half-sent")
    void halfSent(HttpServletResponse response) throws IOException {
      response.getOutputStream().write("partial".getBytes());
      response.flushBuffer();
      throw new IllegalStateException("stream failed");
    }
  }
}
