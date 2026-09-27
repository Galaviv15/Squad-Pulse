package com.squadpulse.common.archunitfixture;

import com.squadpulse.common.PublicEndpoint;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deliberately violates the endpoint-authorization rule by being both {@link PublicEndpoint} and
 * {@code @PreAuthorize} — contradictory, since a public endpoint has no authenticated caller.
 */
@RestController
public class ControllerWithPublicAndPreAuthorizedEndpoint {

  @PostMapping("/archunit-fixture/public-and-pre-authorized")
  @PublicEndpoint
  @PreAuthorize("hasAuthority('ADMIN')")
  public String contradictory() {
    return "ok";
  }
}
