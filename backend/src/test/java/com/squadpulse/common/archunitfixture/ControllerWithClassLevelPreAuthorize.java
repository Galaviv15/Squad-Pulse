package com.squadpulse.common.archunitfixture;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Complies via a class-level {@code @PreAuthorize}, which covers every handler in the class. */
@RestController
@RequestMapping("/archunit-fixture/class-level")
@PreAuthorize("hasAuthority('EDIT_FULL')")
public class ControllerWithClassLevelPreAuthorize {

  @GetMapping
  public String read() {
    return "ok";
  }

  @PostMapping
  public String write() {
    return "ok";
  }
}
