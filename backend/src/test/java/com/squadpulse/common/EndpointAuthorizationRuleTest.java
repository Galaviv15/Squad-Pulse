package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.common.archunitfixture.ControllerWithClassLevelPreAuthorize;
import com.squadpulse.common.archunitfixture.ControllerWithPreAuthorizedEndpoint;
import com.squadpulse.common.archunitfixture.ControllerWithPublicAndPreAuthorizedEndpoint;
import com.squadpulse.common.archunitfixture.ControllerWithPublicEndpoint;
import com.squadpulse.common.archunitfixture.ControllerWithUndeclaredAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * {@link EndpointAuthorizationTest} only proves the real codebase currently passes — true even if
 * the rule's logic were broken. This evaluates the exact same {@link EndpointAuthorizationRules}
 * rule against one fixture per case, each imported on its own so it's proven in isolation.
 */
class EndpointAuthorizationRuleTest {

  @Test
  void failsForHandlersWithNeitherPreAuthorizeNorPublicEndpoint() {
    EvaluationResult result = evaluate(ControllerWithUndeclaredAccess.class);

    assertThat(result.hasViolation()).isTrue();
    String failureReport = result.getFailureReport().toString();
    assertThat(failureReport).contains(".viaRequestMapping(");
    assertThat(failureReport).contains(".viaPatchMapping(");
    assertThat(failureReport).contains("including VIEW_ONLY");
    assertThat(failureReport).doesNotContain(".notAHandler(");
  }

  @Test
  void allowsAMethodLevelPreAuthorize() {
    assertThat(evaluate(ControllerWithPreAuthorizedEndpoint.class).hasViolation()).isFalse();
  }

  @Test
  void allowsAClassLevelPreAuthorize() {
    assertThat(evaluate(ControllerWithClassLevelPreAuthorize.class).hasViolation()).isFalse();
  }

  @Test
  void allowsAPublicEndpoint() {
    assertThat(evaluate(ControllerWithPublicEndpoint.class).hasViolation()).isFalse();
  }

  @Test
  void failsForAHandlerThatIsBothPublicAndPreAuthorized() {
    EvaluationResult result = evaluate(ControllerWithPublicAndPreAuthorizedEndpoint.class);

    assertThat(result.hasViolation()).isTrue();
    assertThat(result.getFailureReport().toString())
        .contains(".contradictory(")
        .contains("also covered by @PreAuthorize");
  }

  private static EvaluationResult evaluate(Class<?> fixture) {
    return EndpointAuthorizationRules.REQUEST_HANDLERS_MUST_DECLARE_WHO_MAY_CALL_THEM.evaluate(
        new ClassFileImporter().importClasses(fixture));
  }
}
