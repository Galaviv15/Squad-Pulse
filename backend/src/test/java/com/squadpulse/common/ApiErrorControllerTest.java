package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import java.io.IOException;
import java.util.List;
import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * {@link ApiErrorController}'s decisions on their own; the real error dispatch is covered by {@link
 * ErrorRenderingIntegrationTest}.
 */
class ApiErrorControllerTest {

  private final ApiErrorController controller = new ApiErrorController();
  private final Logger logger = (Logger) LoggerFactory.getLogger(ApiErrorController.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
  private Level loggerLevel;

  @BeforeEach
  void captureLogs() {
    appender.start();
    logger.addAppender(appender);
    loggerLevel = logger.getLevel();
    logger.setLevel(Level.DEBUG);
  }

  @AfterEach
  void stopCapturing() {
    logger.detachAppender(appender);
    logger.setLevel(loggerLevel);
  }

  @Test
  void aClientErrorKeepsItsStatusUnderAGenericMessageAndIsLoggedAtDebug() throws Exception {
    ResponseEntity<ApiErrorResponse> response = controller.error(errorDispatch(400));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    assertThat(response.getBody().status()).isEqualTo(400);
    assertThat(response.getBody().error()).isEqualTo("Bad Request");
    assertThat(response.getBody().message()).isEqualTo("The request could not be processed");
    assertThat(response.getBody().details()).isEmpty();
    assertThat(theOnlyEvent().getLevel()).isEqualTo(Level.DEBUG);
    assertThat(theOnlyEvent().getFormattedMessage()).isEqualTo("Answered POST /original with 400");
  }

  /** The container's error message can quote the request, so it's never passed on. */
  @Test
  void theContainersErrorMessageIsNeverPassedOn() throws Exception {
    MockHttpServletRequest request = errorDispatch(400);
    request.setAttribute(RequestDispatcher.ERROR_MESSAGE, "rejected /original//marker");

    assertThat(controller.error(request).getBody().toString()).doesNotContain("marker");
  }

  @Test
  void aServerErrorWithoutAnExceptionIsLoggedAtErrorWithoutAStackTrace() throws Exception {
    ResponseEntity<ApiErrorResponse> response = controller.error(errorDispatch(503));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody().error()).isEqualTo("Service Unavailable");
    assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    assertThat(theOnlyEvent().getLevel()).isEqualTo(Level.ERROR);
    assertThat(theOnlyEvent().getFormattedMessage()).isEqualTo("Answered POST /original with 503");
    assertThat(theOnlyEvent().getThrowableProxy()).isNull();
  }

  /** The fallback case: an exception {@link UnhandledExceptionFilter} didn't catch. */
  @Test
  void anExceptionIsLoggedAtErrorWithItsStackTraceButNotPassedOn() throws Exception {
    MockHttpServletRequest request = errorDispatch(500);
    request.setAttribute(
        RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("secret-marker"));

    ResponseEntity<ApiErrorResponse> response = controller.error(request);

    assertThat(response.getBody().message()).isEqualTo("An unexpected error occurred");
    assertThat(response.getBody().toString()).doesNotContain("secret-marker");
    assertThat(theOnlyEvent().getLevel()).isEqualTo(Level.ERROR);
    assertThat(theOnlyEvent().getFormattedMessage())
        .isEqualTo("Unexpected error handling POST /original");
    assertThat(theOnlyEvent().getThrowableProxy().getMessage()).isEqualTo("secret-marker");
  }

  @Test
  void aClientThatWentAwayIsLoggedAtDebugOnly() throws Exception {
    MockHttpServletRequest request = errorDispatch(500);
    request.setAttribute(
        RequestDispatcher.ERROR_EXCEPTION,
        new ClientAbortException(new IOException("Broken pipe")));

    controller.error(request);

    assertThat(theOnlyEvent().getLevel()).isEqualTo(Level.DEBUG);
    assertThat(theOnlyEvent().getFormattedMessage())
        .startsWith("Client disconnected during POST /original");
  }

  /** No status at all, or one that isn't an error. */
  @ParameterizedTest
  @ValueSource(strings = {"missing", "not-a-number", "200", "302", "600"})
  void aMissingOrInvalidStatusIsA500(String status) throws Exception {
    MockHttpServletRequest request = errorDispatch(0);
    switch (status) {
      case "missing" -> request.removeAttribute(RequestDispatcher.ERROR_STATUS_CODE);
      case "not-a-number" -> request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
      default -> request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, Integer.valueOf(status));
    }

    ResponseEntity<ApiErrorResponse> response = controller.error(request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
  }

  /** A status without a standard reason phrase, as {@link GlobalExceptionHandler} words it. */
  @Test
  void anUnknownStatusGetsAGenericReasonPhrase() throws Exception {
    assertThat(controller.error(errorDispatch(499)).getBody().error()).isEqualTo("Client Error");
  }

  /** Answered like an unmapped path: {@link GlobalExceptionHandler} turns this into the 404. */
  @Test
  void aDirectRequestIsNotFound() {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
    request.setDispatcherType(DispatcherType.REQUEST);

    assertThatThrownBy(() -> controller.error(request))
        .isInstanceOfSatisfying(
            NoHandlerFoundException.class,
            ex -> {
              assertThat(ex.getHttpMethod()).isEqualTo("GET");
              assertThat(ex.getRequestURL()).isEqualTo("/error");
            });
    assertThat(appender.list).isEmpty();
  }

  private static MockHttpServletRequest errorDispatch(int status) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/error");
    request.setDispatcherType(DispatcherType.ERROR);
    request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
    request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/original");
    return request;
  }

  private ILoggingEvent theOnlyEvent() {
    List<ILoggingEvent> events = appender.list;
    assertThat(events).hasSize(1);
    return events.get(0);
  }
}
