package com.squadpulse.common.archunitfixture;

import com.squadpulse.common.PublicEndpoint;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Complies via {@link PublicEndpoint}. */
@RestController
public class ControllerWithPublicEndpoint {

  @PostMapping("/archunit-fixture/public")
  @PublicEndpoint
  public String publicAction() {
    return "ok";
  }
}
