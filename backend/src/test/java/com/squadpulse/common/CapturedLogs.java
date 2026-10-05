package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

/**
 * Everything logged anywhere in the JVM while a test runs, captured on the root logger: for tests
 * that must prove something about what the whole application logs (nothing at ERROR, exactly one
 * ERROR and from whom, no secrets), Tomcat's own loggers included. Call {@link #start()} before the
 * test and {@link #stop()} after it.
 *
 * <p>The root logger also receives what other test classes' cached application contexts log on
 * background threads, at any moment. {@link #errors()} and {@link #warningsOrAbove()} therefore
 * leave out {@link #BACKGROUND_DRIVER_LOGGERS}; {@link #events()} and {@link #everythingLogged()}
 * keep every event. Every read goes through one synchronized snapshot.
 */
final class CapturedLogs {

  /**
   * Logger-name prefixes of the client libraries whose background threads log into every test's
   * window (KAN-55): the Redis client (Lettuce) and its Netty event loops, kept alive by cached
   * Spring contexts whose Testcontainers have already stopped, fail to reconnect or to notify
   * listeners. Matched on a package boundary, by logger name only. Taken from what CI logged
   * (Netty's {@code DefaultPromise.rejectedExecution} at ERROR, Lettuce's {@code
   * ConnectionWatchdog} at WARN); sharing the containers across test classes (KAN-54) should make
   * this list unnecessary. Never add Tomcat, Spring or our own packages here.
   */
  static final List<String> BACKGROUND_DRIVER_LOGGERS = List.of("io.netty", "io.lettuce");

  private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  void start() {
    appender.start();
    rootLogger.addAppender(appender);
  }

  void stop() {
    rootLogger.detachAppender(appender);
    appender.stop();
  }

  /**
   * Every captured event, from every logger at every level. {@code AppenderBase.doAppend} holds the
   * appender's monitor while it adds to the list, so copying under the same monitor never sees a
   * half-done add.
   */
  List<ILoggingEvent> events() {
    synchronized (appender) {
      return List.copyOf(appender.list);
    }
  }

  /** ERROR events from every logger, Tomcat's included, except the background drivers'. */
  List<ILoggingEvent> errors() {
    return events().stream()
        .filter(event -> event.getLevel() == Level.ERROR)
        .filter(event -> !isBackgroundDriver(event.getLoggerName()))
        .toList();
  }

  /** WARN and ERROR events from every logger, except the background drivers'. */
  List<ILoggingEvent> warningsOrAbove() {
    return events().stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .filter(event -> !isBackgroundDriver(event.getLoggerName()))
        .toList();
  }

  void assertNoErrors() {
    List<ILoggingEvent> errors = errors();
    assertThat(errors)
        .withFailMessage(() -> "Expected no ERROR, but got:" + describe(errors))
        .isEmpty();
  }

  void assertNoWarningsOrAbove() {
    List<ILoggingEvent> warnings = warningsOrAbove();
    assertThat(warnings)
        .withFailMessage(() -> "Expected nothing at WARN or above, but got:" + describe(warnings))
        .isEmpty();
  }

  ILoggingEvent theOnlyError() {
    List<ILoggingEvent> errors = errors();
    assertThat(errors)
        .withFailMessage(
            () -> "Expected exactly one ERROR, but got " + errors.size() + ":" + describe(errors))
        .hasSize(1);
    return errors.get(0);
  }

  /** Every captured message with its stack trace, as a log file would hold them. Unfiltered. */
  String everythingLogged() {
    StringBuilder all = new StringBuilder();
    for (ILoggingEvent event : events()) {
      all.append(event.getFormattedMessage()).append('\n');
      if (event.getThrowableProxy() != null) {
        all.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
      }
    }
    return all.toString();
  }

  static boolean isBackgroundDriver(String loggerName) {
    return BACKGROUND_DRIVER_LOGGERS.stream()
        .anyMatch(prefix -> loggerName.equals(prefix) || loggerName.startsWith(prefix + "."));
  }

  private static String describe(List<ILoggingEvent> events) {
    return events.stream()
        .map(
            event ->
                "\n  "
                    + event.getLevel()
                    + " ["
                    + event.getThreadName()
                    + "] "
                    + event.getLoggerName()
                    + ": "
                    + event.getFormattedMessage())
        .collect(Collectors.joining());
  }
}
