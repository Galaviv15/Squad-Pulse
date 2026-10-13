package com.squadpulse.match;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/**
 * The shared, {@code @NotClubScoped} standings have exactly one write path (KAN-66): only {@link
 * ScrapeIngestService} calls the standings repository at all besides {@link MatchReadService}'s
 * read, and only the service-token ingest controller calls {@link ScrapeIngestService}. The
 * repository is package-private, so nothing outside {@code match} can reach it in the first place.
 * Likewise for the club-scoped player statistics snapshots (KAN-72).
 */
class StandingsWritePathTest {

  private static final JavaClasses MAIN_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.squadpulse");

  @Test
  void onlyTheIngestServiceWritesTheStandings() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ScrapeIngestService.class.getName())
        .should()
        .callMethodWhere(
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "a write to the standings repository",
                call ->
                    call.getTargetOwner().isAssignableTo(LeagueStandingsSnapshotRepository.class)
                        && !call.getName().startsWith("find")))
        .because("the shared standings must have exactly one write path")
        .check(MAIN_CLASSES);
  }

  /**
   * The player statistics snapshots (KAN-72) are club-scoped, but they too are written only by the
   * ingest: no user-facing path may change scraped data. The repository is package-private, so only
   * {@code match} could call it.
   */
  @Test
  void onlyTheIngestServiceWritesThePlayerStats() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(ScrapeIngestService.class.getName())
        .should()
        .callMethodWhere(
            com.tngtech.archunit.base.DescribedPredicate.describe(
                "a write to the player statistics repository",
                call ->
                    call.getTargetOwner().isAssignableTo(PlayerSeasonStatsSnapshotRepository.class)
                        && !call.getName().startsWith("find")))
        .because("scraped player statistics are written only by the scraper's ingest")
        .check(MAIN_CLASSES);
  }

  @Test
  void onlyTheServiceTokenIngestEndpointCallsTheIngestService() {
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName("com.squadpulse.scrapingintegration.ScrapeResultController")
        .and()
        .doNotHaveFullyQualifiedName(ScrapeIngestService.class.getName())
        .should()
        .callMethod(
            ScrapeIngestService.class,
            "ingest",
            int.class,
            int.class,
            int.class,
            ScrapedFixtures.class,
            ScrapedStandings.class,
            ScrapedPlayerStats.class)
        .because("no user-facing endpoint may reach the standings' write path")
        .check(MAIN_CLASSES);
  }
}
