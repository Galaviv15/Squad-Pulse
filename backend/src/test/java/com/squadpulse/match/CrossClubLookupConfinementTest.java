package com.squadpulse.match;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.ClubRepository;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * The lookups across all clubs by football.org.il mapping (KAN-66) stay on the scraper's ingest
 * path: {@link ClubFootballMappings#clubIdsMappedTo} and {@link
 * ClubFootballMappings#anyClubMappedTo} are called only by {@link ScrapeIngestService}, and the
 * {@link ClubRepository} queries behind them only by {@link ClubFootballMappings}. Otherwise a
 * user-facing path could find another club's id and {@code callAs} into it. {@link
 * ClubFootballMappings#currentClubMapping()} is scoped by {@code ClubContext}, so it isn't
 * restricted.
 */
class CrossClubLookupConfinementTest {

  private static final JavaClasses MAIN_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.squadpulse");

  static final ArchRule ONLY_THE_INGEST_LOOKS_UP_CLUBS_BY_MAPPING =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(ScrapeIngestService.class.getName())
          .should()
          .callMethod(
              ClubFootballMappings.class, "clubIdsMappedTo", int.class, int.class, int.class)
          .orShould()
          .callMethod(ClubFootballMappings.class, "anyClubMappedTo", int.class, int.class)
          .because("a lookup across all clubs must stay on the scraper's ingest path");

  static final ArchRule ONLY_THE_MAPPING_API_RUNS_THE_CROSS_CLUB_QUERIES =
      noClasses()
          .that()
          .doNotHaveFullyQualifiedName(ClubFootballMappings.class.getName())
          .should()
          .callMethod(
              ClubRepository.class,
              "findIdsByFootballOrgIl",
              Integer.class,
              Integer.class,
              Integer.class)
          .orShould()
          .callMethod(
              ClubRepository.class, "existsByFootballOrgIlLeague", Integer.class, Integer.class)
          .because("the cross-club queries are reached only through ClubFootballMappings");

  @Test
  void onlyTheIngestLooksUpClubsByMapping() {
    ONLY_THE_INGEST_LOOKS_UP_CLUBS_BY_MAPPING.check(MAIN_CLASSES);
  }

  @Test
  void onlyTheMappingApiRunsTheCrossClubQueries() {
    ONLY_THE_MAPPING_API_RUNS_THE_CROSS_CLUB_QUERIES.check(MAIN_CLASSES);
  }
}
