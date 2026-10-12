package com.squadpulse.scrapingintegration;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Runs {@link InternalEndpointRules} against every controller in the main sources. */
class InternalEndpointAuthorizationTest {

  @Test
  void internalHandlersAndTheScraperAuthorityGoTogether() {
    List<Class<?>> mainClasses =
        new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.squadpulse")
                .stream()
                .map(JavaClass::reflect)
                .<Class<?>>map(type -> type)
                .toList();

    assertThat(InternalEndpointRules.violations(mainClasses)).isEmpty();
    // And the rule did see the one internal handler.
    assertThat(mainClasses).contains(ScrapeResultController.class);
  }
}
