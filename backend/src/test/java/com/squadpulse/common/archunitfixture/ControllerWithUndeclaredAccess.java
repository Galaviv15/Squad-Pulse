package com.squadpulse.common.archunitfixture;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Deliberately violates the endpoint-authorization rule twice: one handler mapped with plain
 * {@code @RequestMapping}, one with the composed {@code @PatchMapping} — proving the rule finds
 * handlers via meta-annotations, and on a plain {@code @Controller} as well as a
 * {@code @RestController}. {@link #notAHandler()} has no mapping and must not be flagged. Used only
 * by {@code EndpointAuthorizationRuleTest}.
 */
@Controller
@ResponseBody
public class ControllerWithUndeclaredAccess {

  @RequestMapping("/archunit-fixture/undeclared/request-mapping")
  public String viaRequestMapping() {
    return "ok";
  }

  @PatchMapping("/archunit-fixture/undeclared/patch-mapping")
  public String viaPatchMapping() {
    return "ok";
  }

  public String notAHandler() {
    return "ok";
  }
}
