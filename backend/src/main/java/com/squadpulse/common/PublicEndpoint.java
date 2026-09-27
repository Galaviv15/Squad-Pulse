package com.squadpulse.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as <b>deliberately</b> carrying no {@code @PreAuthorize}: it's
 * reachable without an access token, because it authenticates by other means (e.g. login by email +
 * password).
 *
 * <p>An ArchUnit rule ({@code EndpointAuthorizationRules}) fails the build unless every request
 * handler declares its access one way or the other: {@code @PreAuthorize} (on the method or its
 * class) or this annotation. Without that, a handler that simply forgot its {@code @PreAuthorize}
 * would be reachable by every authenticated user of the club, including {@code VIEW_ONLY}.
 *
 * <p>This annotation doesn't open anything up by itself: the security chain only lets a request
 * through without a token if its path is in {@code auth.SecurityConfig.PUBLIC_ENDPOINTS}, and a
 * test ({@code PublicEndpointsConsistencyTest}) fails if the two ever disagree. So making an
 * endpoint public takes both — and each one should be reviewed on its own merits.
 *
 * <p>Lives in {@code common}, next to {@link GloballyScoped} and {@link NotClubScoped}, because
 * every module's controllers are subject to the rule, and modules depend on {@code common}, not on
 * {@code auth}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PublicEndpoint {}
