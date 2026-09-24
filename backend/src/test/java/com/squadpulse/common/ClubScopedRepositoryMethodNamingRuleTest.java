package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.squadpulse.common.archunitfixture.FixtureClubScopedEntity;
import com.squadpulse.common.archunitfixture.RepositoryMixingGloballyScopedAndUnscopedFinders;
import com.squadpulse.common.archunitfixture.RepositoryWithGloballyScopedFinder;
import com.squadpulse.common.archunitfixture.RepositoryWithScopedFinder;
import com.squadpulse.common.archunitfixture.RepositoryWithUnscopedFinder;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * {@link ClubScopedRepositoryMethodNamingTest} only proves the rule currently passes against a
 * codebase that has no custom repository methods at all yet — that would be true even if the rule's
 * logic were completely broken. This test proves the rule actually does something: it evaluates the
 * exact same {@link ClubScopedRepositoryRules} rule against two small fixtures and asserts it fails
 * one and passes the other.
 *
 * <p>Each fixture pairs a derived-query method (e.g. {@code findByName}) with an
 * {@code @Query}-annotated method whose name matches no derivation convention at all (e.g. {@code
 * lookupSomehow}) — proving the rule's structural "declared directly on this interface" check
 * catches an unscoped method regardless of how it's implemented internally, unlike the prefix-regex
 * this rule used to rely on.
 *
 * <p>The {@link GloballyScoped} fixtures prove the escape hatch works in both directions: the
 * annotation alone satisfies the rule, but only for the exact method it's on — an unannotated
 * sibling without {@code ClubId} still fails.
 */
class ClubScopedRepositoryMethodNamingRuleTest {

  @Test
  void failsForBothAnUnscopedDerivedQueryAndAnUnscopedQueryAnnotatedMethod() {
    // importClasses, not importPackagesOf: the fixture package also contains the compliant
    // repository, and this must prove the rule in isolation on just the violating one.
    JavaClasses fixture =
        new ClassFileImporter()
            .importClasses(RepositoryWithUnscopedFinder.class, FixtureClubScopedEntity.class);

    EvaluationResult result =
        ClubScopedRepositoryRules.DERIVED_FINDERS_ON_CLUB_SCOPED_REPOSITORIES_MUST_INCLUDE_CLUB_ID
            .evaluate(fixture);

    assertThat(result.hasViolation()).isTrue();
    String failureReport = result.getFailureReport().toString();
    assertThat(failureReport).contains("findByName");
    assertThat(failureReport).contains("lookupSomehow");
  }

  @Test
  void allowsBothAScopedDerivedQueryAndAScopedQueryAnnotatedMethod() {
    JavaClasses fixture =
        new ClassFileImporter()
            .importClasses(RepositoryWithScopedFinder.class, FixtureClubScopedEntity.class);

    EvaluationResult result =
        ClubScopedRepositoryRules.DERIVED_FINDERS_ON_CLUB_SCOPED_REPOSITORIES_MUST_INCLUDE_CLUB_ID
            .evaluate(fixture);

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void allowsAGloballyScopedMethodWithoutClubIdInItsName() {
    JavaClasses fixture =
        new ClassFileImporter()
            .importClasses(RepositoryWithGloballyScopedFinder.class, FixtureClubScopedEntity.class);

    EvaluationResult result =
        ClubScopedRepositoryRules.DERIVED_FINDERS_ON_CLUB_SCOPED_REPOSITORIES_MUST_INCLUDE_CLUB_ID
            .evaluate(fixture);

    assertThat(result.hasViolation()).isFalse();
  }

  @Test
  void stillFailsAnUnannotatedMethodWithoutClubIdNextToAGloballyScopedOne() {
    JavaClasses fixture =
        new ClassFileImporter()
            .importClasses(
                RepositoryMixingGloballyScopedAndUnscopedFinders.class,
                FixtureClubScopedEntity.class);

    EvaluationResult result =
        ClubScopedRepositoryRules.DERIVED_FINDERS_ON_CLUB_SCOPED_REPOSITORIES_MUST_INCLUDE_CLUB_ID
            .evaluate(fixture);

    assertThat(result.hasViolation()).isTrue();
    String failureReport = result.getFailureReport().toString();
    assertThat(failureReport).contains(".findByName(");
    assertThat(failureReport).doesNotContain(".findByCode(");
  }
}
