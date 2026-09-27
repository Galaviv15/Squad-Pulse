package com.squadpulse.common.archunitfixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Complies via a method-level {@code @PreAuthorize}. */
@RestController
public class ControllerWithPreAuthorizedEndpoint {

  @GetMapping("/archunit-fixture/pre-authorized")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  public String read() {
    return "ok";
  }
}
