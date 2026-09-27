package com.squadpulse.common;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Fails the build if any real controller method in this codebase handles requests without declaring
 * who may call it — {@code @PreAuthorize} or {@link PublicEndpoint}. See {@link
 * EndpointAuthorizationRules} for why.
 *
 * <p>Only scans main sources ({@link ImportOption.DoNotIncludeTests}): test-only probe controllers
 * and the deliberately violating fixtures in {@code archunitfixture} aren't production code.
 */
@AnalyzeClasses(packages = "com.squadpulse", importOptions = ImportOption.DoNotIncludeTests.class)
class EndpointAuthorizationTest {

  @ArchTest
  static final ArchRule requestHandlersMustDeclareWhoMayCallThem =
      EndpointAuthorizationRules.REQUEST_HANDLERS_MUST_DECLARE_WHO_MAY_CALL_THEM;
}
