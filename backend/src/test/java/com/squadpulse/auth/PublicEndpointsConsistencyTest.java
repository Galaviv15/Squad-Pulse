package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.common.PublicEndpoint;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * An endpoint is only really public if it's both marked {@link PublicEndpoint} (which satisfies the
 * ArchUnit rule) and listed in {@link SecurityConfig#PUBLIC_ENDPOINTS} (which is what actually lets
 * a request through without a token). This fails if the two ever disagree — a method marked public
 * that the chain still blocks, or a path the chain lets through whose handler isn't marked (and so
 * wasn't reviewed as public).
 *
 * <p>Each marked method's mapping is resolved the way Spring MVC resolves it: the class-level
 * {@code @RequestMapping} path joined with the method-level one, via merged annotations so
 * {@code @PostMapping} etc. count. {@code SecurityConfig} permits {@link #PUBLIC_METHOD POST} only,
 * so each is compared as {@code "POST /path"}; a public handler mapped to another (or to every)
 * HTTP method shows up as a mismatch.
 */
class PublicEndpointsConsistencyTest {

  private static final String PUBLIC_METHOD = "POST";

  @Test
  void publicEndpointMethodsAndSecurityConfigPublicEndpointsAgree() {
    Set<String> permittedByTheChain =
        Arrays.stream(SecurityConfig.PUBLIC_ENDPOINTS)
            .map(path -> PUBLIC_METHOD + " " + path)
            .collect(Collectors.toSet());

    assertThat(publicEndpointMappings()).isNotEmpty().isEqualTo(permittedByTheChain);
  }

  private static Set<String> publicEndpointMappings() {
    return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.squadpulse")
            .stream()
            .flatMap(javaClass -> javaClass.getMethods().stream())
            .filter(method -> method.isAnnotatedWith(PublicEndpoint.class))
            .map(JavaMethod::reflect)
            .flatMap(method -> mappings(method).stream())
            .collect(Collectors.toSet());
  }

  private static Set<String> mappings(Method method) {
    RequestMapping methodMapping =
        AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
    assertThat(methodMapping).as("request mapping of @PublicEndpoint %s", method).isNotNull();
    RequestMapping classMapping =
        AnnotatedElementUtils.findMergedAnnotation(
            method.getDeclaringClass(), RequestMapping.class);

    String[] httpMethods =
        methodMapping.method().length == 0
            ? new String[] {"<any method>"}
            : Arrays.stream(methodMapping.method()).map(Enum::name).toArray(String[]::new);
    Set<String> mappings = new HashSet<>();
    for (String prefix : pathsOrEmpty(classMapping)) {
      for (String path : pathsOrEmpty(methodMapping)) {
        for (String httpMethod : httpMethods) {
          mappings.add(httpMethod + " " + prefix + path);
        }
      }
    }
    return mappings;
  }

  private static String[] pathsOrEmpty(RequestMapping mapping) {
    return mapping == null || mapping.path().length == 0 ? new String[] {""} : mapping.path();
  }
}
