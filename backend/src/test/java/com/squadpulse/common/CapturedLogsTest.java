package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * {@link CapturedLogs} leaves out background drivers by logger name alone (KAN-55): never by
 * thread, so an event from a Tomcat or application thread still counts, and never from {@link
 * CapturedLogs#everythingLogged()}. Events come from other threads, like the real noise does.
 */
class CapturedLogsTest {

  private final CapturedLogs logs = new CapturedLogs();

  @BeforeEach
  void captureLogs() {
    logs.start();
  }

  @AfterEach
  void stopCapturing() {
    logs.stop();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"io.netty.util.concurrent.DefaultPromise.rejectedExecution", "io.lettuce"})
  void aBackgroundDriverErrorIsNeitherAnErrorNorAWarning(String loggerName) throws Exception {
    logFromAnotherThread(loggerName, Level.ERROR, "driver error");

    logs.assertNoErrors();
    logs.assertNoWarningsOrAbove();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"io.netty.channel.Whatever", "io.lettuce.core.protocol.ConnectionWatchdog"})
  void aBackgroundDriverWarningIsNotAWarning(String loggerName) throws Exception {
    logFromAnotherThread(loggerName, Level.WARN, "driver warning");

    logs.assertNoWarningsOrAbove();
  }

  @Test
  void everyDenyListedPrefixIsLeftOutOfErrors() throws Exception {
    for (String prefix : CapturedLogs.BACKGROUND_DRIVER_LOGGERS) {
      logFromAnotherThread(prefix + ".Something", Level.ERROR, "driver error");
      logFromAnotherThread(prefix + ".Something", Level.WARN, "driver warning");
    }

    logs.assertNoErrors();
    logs.assertNoWarningsOrAbove();
  }

  @Test
  void backgroundDriverErrorsDoNotBreakTheOnlyError() throws Exception {
    logFromAnotherThread("io.lettuce.core.protocol.ConnectionWatchdog", Level.ERROR, "before");
    logFromAnotherThread("com.squadpulse.whatever.Service", Level.ERROR, "ours");
    logFromAnotherThread("io.netty.util.concurrent.DefaultPromise", Level.ERROR, "after");

    assertThat(logs.theOnlyError().getFormattedMessage()).isEqualTo("ours");
  }

  /** Not filtered by thread: Tomcat's and our errors count from any thread. */
  @Test
  void tomcatAndApplicationErrorsFromAnotherThreadAreErrors() throws Exception {
    logFromAnotherThread(
        "org.apache.catalina.core.StandardWrapperValve", Level.ERROR, "tomcat error");
    logFromAnotherThread("com.squadpulse.whatever", Level.ERROR, "our error");
    logFromAnotherThread("org.apache.coyote.http11.Http11Processor", Level.WARN, "tomcat warning");

    assertThat(logs.errors())
        .extracting(ILoggingEvent::getLoggerName)
        .contains("org.apache.catalina.core.StandardWrapperValve", "com.squadpulse.whatever");
    assertThat(logs.warningsOrAbove())
        .extracting(ILoggingEvent::getLoggerName)
        .contains(
            "org.apache.catalina.core.StandardWrapperValve",
            "com.squadpulse.whatever",
            "org.apache.coyote.http11.Http11Processor");
  }

  /** A prefix matches whole package segments only. */
  @ParameterizedTest
  @ValueSource(strings = {"io.lettucefoo.Bar", "io.nettyx", "com.example.io.netty.Thing"})
  void aNearMissNameIsAnError(String loggerName) throws Exception {
    logFromAnotherThread(loggerName, Level.ERROR, "near miss");

    assertThat(logs.theOnlyError().getLoggerName()).isEqualTo(loggerName);
  }

  @Test
  void aBackgroundDriverEventIsStillInEverythingLogged() throws Exception {
    logFromAnotherThread("io.netty.util.concurrent.DefaultPromise", Level.ERROR, "netty-marker");
    logFromAnotherThread("io.lettuce.core.RedisClient", Level.INFO, "lettuce-marker");

    assertThat(logs.everythingLogged()).contains("netty-marker").contains("lettuce-marker");
    assertThat(logs.events())
        .extracting(ILoggingEvent::getFormattedMessage)
        .contains("netty-marker", "lettuce-marker");
  }

  @Test
  void theOnlyErrorsFailureNamesTheOffendingEvents() throws Exception {
    logFromAnotherThread("com.squadpulse.first.Service", Level.ERROR, "first error");
    logFromAnotherThread("org.apache.catalina.core.StandardWrapperValve", Level.ERROR, "second");

    assertThatThrownBy(logs::theOnlyError)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Expected exactly one ERROR, but got 2")
        .hasMessageContaining("ERROR [noise-thread] com.squadpulse.first.Service: first error")
        .hasMessageContaining(
            "ERROR [noise-thread] org.apache.catalina.core.StandardWrapperValve: second");
  }

  @Test
  void assertNoErrorsFailureNamesTheOffendingEvent() throws Exception {
    logFromAnotherThread("com.squadpulse.first.Service", Level.ERROR, "an error");

    assertThatThrownBy(logs::assertNoErrors)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("ERROR [noise-thread] com.squadpulse.first.Service: an error");
  }

  private static void logFromAnotherThread(String loggerName, Level level, String message)
      throws InterruptedException {
    Thread thread =
        new Thread(
            () -> LoggerFactory.getLogger(loggerName).atLevel(toSlf4j(level)).log(message),
            "noise-thread");
    thread.start();
    thread.join();
  }

  private static org.slf4j.event.Level toSlf4j(Level level) {
    return org.slf4j.event.Level.valueOf(level.toString());
  }
}
