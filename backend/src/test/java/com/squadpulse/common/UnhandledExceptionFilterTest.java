package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link UnhandledExceptionFilter} on its own: which exceptions it catches, and what it writes. On
 * a real server, with Tomcat's logging, see {@link ErrorRenderingIntegrationTest}.
 */
class UnhandledExceptionFilterTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final UnhandledExceptionFilter filter = new UnhandledExceptionFilter(JSON);
  private final MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/clubs/me");
  private final MockHttpServletResponse response = new MockHttpServletResponse();
  private final Logger logger = (Logger) LoggerFactory.getLogger(UnhandledExceptionFilter.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void captureLogs() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void stopCapturing() {
    logger.detachAppender(appender);
  }

  @Test
  void aCheckedExceptionIsAJson500() throws Exception {
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          throw new ServletException("secret-marker", new IOException("disk"));
        });

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentType()).isEqualTo("application/json");
    JsonNode body = JSON.readTree(response.getContentAsString());
    assertThat(body.get("status").asInt()).isEqualTo(500);
    assertThat(body.get("error").asString()).isEqualTo("Internal Server Error");
    assertThat(body.get("message").asString()).isEqualTo("An unexpected error occurred");
    assertThat(body.get("details").isEmpty()).isTrue();
    assertThat(body.has("code")).isTrue();
    assertThat(body.get("code").isNull()).isTrue();
    assertThat(response.getContentAsString()).doesNotContain("secret-marker");
    assertThat(errors()).singleElement();
    assertThat(errors().get(0).getFormattedMessage())
        .isEqualTo("Unexpected error handling PATCH /clubs/me");
  }

  /** Whatever a filter had buffered but not yet sent is discarded, not prefixed to the JSON. */
  @Test
  void anUncommittedPartialBodyIsDiscarded() throws Exception {
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          res.getOutputStream().write("half-written".getBytes());
          throw new IllegalStateException("bug");
        });

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(response.getContentAsString()).doesNotContain("half-written").startsWith("{");
  }

  /** Not an {@code Exception}: left to the container. */
  @Test
  void anErrorIsNotCaught() {
    assertThatThrownBy(
            () ->
                filter.doFilter(
                    request,
                    response,
                    (req, res) -> {
                      throw new StackOverflowError();
                    }))
        .isInstanceOf(StackOverflowError.class);
    assertThat(appender.list).isEmpty();
  }

  @Test
  void aRequestThatSucceedsIsLeftAlone() throws Exception {
    filter.doFilter(request, response, (req, res) -> res.getWriter().write("ok"));

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getContentAsString()).isEqualTo("ok");
    assertThat(appender.list).isEmpty();
  }

  private List<ILoggingEvent> errors() {
    return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
  }
}
