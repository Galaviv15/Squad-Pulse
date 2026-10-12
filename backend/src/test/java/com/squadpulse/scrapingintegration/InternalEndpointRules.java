package com.squadpulse.scrapingintegration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The rule that ties {@code /internal/**} to the scraper's service token (KAN-66), shared by {@link
 * InternalEndpointAuthorizationTest} (the real codebase) and {@link
 * InternalEndpointAuthorizationRuleTest} (fixtures proving it catches each violation):
 *
 * <ul>
 *   <li>every request handler under {@code /internal/**} requires exactly {@value #REQUIRED};
 *   <li>every handler whose {@code @PreAuthorize} mentions {@code SCRAPER_SERVICE} is under {@code
 *       /internal/**}, and requires exactly that.
 * </ul>
 *
 * <p>Mappings are resolved like Spring MVC does (class path joined with the method's, merged
 * annotations so {@code @PostMapping} counts); {@code @PreAuthorize} on the method or its class.
 */
final class InternalEndpointRules {

  static final String REQUIRED =
      "hasAuthority('" + InternalSecurityConfig.SCRAPER_SERVICE_AUTHORITY + "')";

  private InternalEndpointRules() {}

  /** One message per violating handler; empty if all comply. */
  static List<String> violations(Collection<Class<?>> classes) {
    List<String> violations = new ArrayList<>();
    for (Class<?> type : classes) {
      if (!AnnotatedElementUtils.hasAnnotation(type, Controller.class)) {
        continue;
      }
      for (Method method : type.getDeclaredMethods()) {
        RequestMapping mapping =
            AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        if (mapping == null) {
          continue;
        }
        List<String> paths = paths(type, mapping);
        boolean anyInternal = paths.stream().anyMatch(InternalEndpointRules::isInternal);
        boolean allInternal = paths.stream().allMatch(InternalEndpointRules::isInternal);
        String expression = preAuthorize(method);
        boolean exact = REQUIRED.equals(expression);
        boolean mentionsScraper =
            expression != null
                && expression.contains(InternalSecurityConfig.SCRAPER_SERVICE_AUTHORITY);
        String name = type.getName() + "." + method.getName() + " " + paths;
        if (anyInternal && !(allInternal && exact)) {
          violations.add(name + " is under /internal/** but doesn't require exactly " + REQUIRED);
        } else if (mentionsScraper && !(allInternal && exact)) {
          violations.add(
              name + " mentions SCRAPER_SERVICE but isn't an /internal/** handler requiring it");
        }
      }
    }
    return violations;
  }

  private static boolean isInternal(String path) {
    return path.equals("/internal") || path.startsWith("/internal/");
  }

  private static List<String> paths(Class<?> type, RequestMapping methodMapping) {
    RequestMapping classMapping =
        AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
    List<String> prefixes =
        classMapping == null || classMapping.path().length == 0
            ? List.of("")
            : Arrays.asList(classMapping.path());
    List<String> suffixes =
        methodMapping.path().length == 0 ? List.of("") : Arrays.asList(methodMapping.path());
    List<String> paths = new ArrayList<>();
    for (String prefix : prefixes) {
      for (String suffix : suffixes) {
        paths.add(prefix + suffix);
      }
    }
    return paths;
  }

  private static String preAuthorize(Method method) {
    PreAuthorize onMethod = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
    if (onMethod != null) {
      return onMethod.value();
    }
    PreAuthorize onClass =
        AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), PreAuthorize.class);
    return onClass == null ? null : onClass.value();
  }
}
