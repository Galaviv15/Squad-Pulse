package com.squadpulse.common;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Fails the build if any real repository in this codebase declares a derived query method (e.g.
 * {@code findByEmail(...)}) for a {@link ClubScopedEntity} without {@code ClubId} in its name —
 * such a method would be built by Spring Data directly, bypassing {@link ClubScopedRepositoryImpl}
 * and leaking data across clubs. See that class's Javadoc for why this can't be caught at runtime.
 *
 * <p>Only scans main sources ({@link ImportOption.DoNotIncludeTests}): this codebase's own
 * test-only fixtures (including the ones deliberately violating this rule in {@code
 * archunitfixture}, used by {@code ClubScopedRepositoryMethodNamingRuleTest} to prove the rule
 * itself works) are not production code and shouldn't fail this check.
 */
@AnalyzeClasses(packages = "com.squadpulse", importOptions = ImportOption.DoNotIncludeTests.class)
class ClubScopedRepositoryMethodNamingTest {

  @ArchTest
  static final ArchRule derivedFindersOnClubScopedRepositoriesMustIncludeClubId =
      ClubScopedRepositoryRules.DERIVED_FINDERS_ON_CLUB_SCOPED_REPOSITORIES_MUST_INCLUDE_CLUB_ID;
}
