package com.squadpulse.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.properties.CanBeAnnotated;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.lang.annotation.Annotation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Shared between {@link EndpointAuthorizationTest} (runs the rule against the real codebase) and
 * {@link EndpointAuthorizationRuleTest} (proves against fixtures that the rule catches a violation
 * and allows each compliant case), so both exercise the exact same {@link ArchRule} — the same
 * pattern as {@link ClubScopedRepositoryRules}.
 *
 * <p>Default-deny for RBAC: the security chain only requires <i>some</i> valid access token for a
 * non-public endpoint, so a handler without {@code @PreAuthorize} is reachable by every
 * authenticated user of the club, {@code VIEW_ONLY} included. That's too easy to do by accident
 * (just forget the annotation), so every request handler must say explicitly who may call it:
 * {@code @PreAuthorize} on the method or its class, or {@link PublicEndpoint}. Not both — a public
 * endpoint has no authenticated caller for {@code @PreAuthorize} to check.
 *
 * <p>"Request handler" means any method of a {@code @Controller} class (incl.
 * {@code @RestController}) annotated with {@code @RequestMapping} or any annotation composed from
 * it ({@code @GetMapping}, {@code @PatchMapping}, ...). All three annotations are matched directly
 * <i>or</i> as meta-annotations, so a composed annotation like a custom {@code @AdminOnly} carrying
 * {@code @PreAuthorize} counts too.
 *
 * <p>Exactly one class is exempt: {@link ApiErrorController}, see {@link #isExempt}.
 */
final class EndpointAuthorizationRules {

  static final ArchRule REQUEST_HANDLERS_MUST_DECLARE_WHO_MAY_CALL_THEM =
      methods()
          .that(
              DescribedPredicate.describe(
                  "handle requests in a controller (other than ApiErrorController)",
                  EndpointAuthorizationRules::isRequestHandler))
          .should(
              new ArchCondition<JavaMethod>(
                  "be annotated with @PreAuthorize (on the method or its class) or"
                      + " @PublicEndpoint, but not both") {
                @Override
                public void check(JavaMethod method, ConditionEvents events) {
                  boolean preAuthorized =
                      hasAnnotation(method, PreAuthorize.class)
                          || hasAnnotation(method.getOwner(), PreAuthorize.class);
                  boolean publicEndpoint = hasAnnotation(method, PublicEndpoint.class);
                  String message;
                  if (preAuthorized && publicEndpoint) {
                    message =
                        ("%s is marked @PublicEndpoint but is also covered by @PreAuthorize — a"
                                + " public endpoint has no authenticated caller for @PreAuthorize"
                                + " to check. Remove one of them.")
                            .formatted(method.getFullName());
                  } else if (preAuthorized || publicEndpoint) {
                    message = method.getFullName() + " declares who may call it";
                  } else {
                    message =
                        ("%s handles requests but has neither @PreAuthorize (on it or its class)"
                                + " nor @PublicEndpoint, so it's reachable by every authenticated"
                                + " user of the club, including VIEW_ONLY. Add"
                                + " @PreAuthorize(\"hasAuthority('...')\") with the lowest"
                                + " PermissionLevel that may use it (VIEW_ONLY for a read"
                                + " endpoint). Only if it must work without an access token,"
                                + " mark it @PublicEndpoint and add its path to"
                                + " SecurityConfig.PUBLIC_ENDPOINTS.")
                            .formatted(method.getFullName());
                  }
                  events.add(
                      new SimpleConditionEvent(method, preAuthorized != publicEndpoint, message));
                }
              })
          .because(
              "a request handler without an explicit authorization rule is reachable by every"
                  + " authenticated user of the club, including VIEW_ONLY");

  private EndpointAuthorizationRules() {}

  private static boolean isRequestHandler(JavaMethod method) {
    return hasAnnotation(method.getOwner(), Controller.class)
        && hasAnnotation(method, RequestMapping.class)
        && !isExempt(method);
  }

  /**
   * {@link ApiErrorController} renders the servlet container's {@code /error} dispatch (KAN-35),
   * which has no authenticated caller — so {@code @PreAuthorize} would deny it — and isn't a public
   * endpoint either ({@code @PublicEndpoint} means a POST listed in {@code
   * SecurityConfig.PUBLIC_ENDPOINTS}). It only renders an error that already happened, exposing
   * nothing. Exempted by exact class, not as "any {@code ErrorController}", so no other controller
   * escapes the rule just by implementing that interface.
   */
  private static boolean isExempt(JavaMethod method) {
    return method.getOwner().isEquivalentTo(ApiErrorController.class);
  }

  private static boolean hasAnnotation(
      CanBeAnnotated element, Class<? extends Annotation> annotationType) {
    return element.isAnnotatedWith(annotationType) || element.isMetaAnnotatedWith(annotationType);
  }
}
