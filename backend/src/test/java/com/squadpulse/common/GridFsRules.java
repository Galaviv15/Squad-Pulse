package com.squadpulse.common;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

/**
 * Shared between {@link GridFsConfinementTest} (runs the rule against the real codebase) and {@link
 * GridFsConfinementRuleTest} (proves against a fixture that the rule catches a violation) — the
 * same pattern as {@link ClubScopedRepositoryRules}.
 *
 * <p>GridFS isn't a Spring Data repository, so the club-scoped repository layer doesn't protect it:
 * every GridFS query is hand-built, and nothing would catch one missing its clubId criterion. So
 * GridFS stays inside {@code common}, where {@link GridFsImageStorage} applies the clubId to every
 * query itself, and everything else goes through {@link ImageStorage}. Matched on the package
 * itself, not {@code common..}: a subpackage of {@code common} is outside it too.
 */
final class GridFsRules {

  static final ArchRule ONLY_COMMON_MAY_USE_GRIDFS =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.squadpulse.common")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.data.mongodb.gridfs..", "com.mongodb.client.gridfs..")
          .because(
              "GridFS queries bypass the club-scoped repository layer; only"
                  + " common.GridFsImageStorage may use GridFS, and it applies the clubId to every"
                  + " query itself — use common.ImageStorage instead");

  private GridFsRules() {}
}
