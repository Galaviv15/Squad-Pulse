package com.squadpulse.scrapingintegration;

import com.squadpulse.common.BadRequestException;
import com.squadpulse.match.IngestResult;
import com.squadpulse.match.ScrapeIngestService;
import com.squadpulse.match.ScrapedFixtures;
import com.squadpulse.match.ScrapedGameDetails;
import com.squadpulse.match.ScrapedPlayerStats;
import com.squadpulse.match.ScrapedStandings;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.FixturesPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.GameDetailsPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.PartStatus;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.PlayerStatsPart;
import com.squadpulse.scrapingintegration.ScrapeResultRequest.StandingsPart;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/scrape-results} (KAN-66): where the scraper posts each scrape. Reachable
 * only with the scraper's service token ({@link InternalSecurityConfig}), never with a user's
 * access token; see docs/scraper-backend-contract.md for the contract.
 *
 * <p>The request is validated as a whole first — Bean Validation on {@link ScrapeResultRequest},
 * unknown properties ({@link ScrapeResultJsonConfig}), the 256 KB body limit ({@link
 * ScrapeResultBodyLimit}), and a {@code fetchedAt} at most {@link #CLOCK_SKEW} in the future here —
 * so a 400 writes nothing. A part the scraper reports as {@code FAILED} is logged at INFO with its
 * reason and the ids only ({@code detail} at DEBUG) and written nowhere. The successful parts go to
 * {@link ScrapeIngestService}.
 *
 * <p>Once the ingest has returned normally, the outcomes it computed set the status of the run
 * named by {@code runId} ({@link ScrapeRunStatusService#recordResult}, KAN-68): so a 400, 409 or
 * 5xx never changes a status, and a failure to record one never changes this response.
 *
 * <p>No request body is ever logged.
 */
@RestController
class ScrapeResultController {

  /** How far ahead of this server's clock the scraper's {@code fetchedAt} may be. */
  static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

  private static final Logger log = LoggerFactory.getLogger(ScrapeResultController.class);

  private final ScrapeIngestService ingestService;
  private final ScrapeRunStatusService statusService;
  private final Clock clock;

  @Autowired
  ScrapeResultController(ScrapeIngestService ingestService, ScrapeRunStatusService statusService) {
    this(ingestService, statusService, Clock.systemUTC());
  }

  ScrapeResultController(
      ScrapeIngestService ingestService, ScrapeRunStatusService statusService, Clock clock) {
    this.ingestService = ingestService;
    this.statusService = statusService;
    this.clock = clock;
  }

  @PostMapping(path = "/internal/scrape-results", consumes = MediaType.APPLICATION_JSON_VALUE)
  @PreAuthorize("hasAuthority('" + InternalSecurityConfig.SCRAPER_SERVICE_AUTHORITY + "')")
  ScrapeResultResponse ingest(@Valid @RequestBody ScrapeResultRequest request) {
    Instant latest = clock.instant().plus(CLOCK_SKEW);
    if (request.fixtures() != null && isAfter(request.fixtures().fetchedAt(), latest)) {
      throw new BadRequestException("fixtures.fetchedAt", "must not be in the future");
    }
    if (request.standings() != null && isAfter(request.standings().fetchedAt(), latest)) {
      throw new BadRequestException("standings.fetchedAt", "must not be in the future");
    }
    if (request.playerStats() != null && isAfter(request.playerStats().fetchedAt(), latest)) {
      throw new BadRequestException("playerStats.fetchedAt", "must not be in the future");
    }
    if (request.gameDetails() != null && isAfter(request.gameDetails().fetchedAt(), latest)) {
      throw new BadRequestException("gameDetails.fetchedAt", "must not be in the future");
    }

    boolean fixturesFailed = reportFailure("fixtures", request, request.fixtures());
    boolean standingsFailed = reportFailure("standings", request, request.standings());
    boolean playerStatsFailed = reportFailure("playerStats", request, request.playerStats());
    boolean gameDetailsFailed = reportFailure("gameDetails", request, request.gameDetails());
    IngestResult result =
        ingestService.ingest(
            request.teamId(),
            request.leagueId(),
            request.seasonId(),
            fixturesFailed ? null : toFixtures(request.fixtures()),
            standingsFailed ? null : toStandings(request.standings()),
            playerStatsFailed ? null : toPlayerStats(request.playerStats()),
            gameDetailsFailed ? null : toGameDetails(request.gameDetails()));

    ScrapeResultResponse response =
        new ScrapeResultResponse(
            fixturesFailed ? failureRecorded() : outcome(result.fixtures()),
            standingsFailed ? failureRecorded() : outcome(result.standings()),
            playerStatsFailed ? failureRecorded() : outcome(result.playerStats()),
            gameDetailsFailed ? failureRecorded() : outcome(result.gameDetails()),
            result.clubsUpdated(),
            result.warnings().stream()
                .map(warning -> new ScrapeResultResponse.Warning(warning.code(), warning.count()))
                .toList());
    statusService.recordResult(request, response);
    return response;
  }

  private static boolean isAfter(Instant fetchedAt, Instant latest) {
    return fetchedAt != null && fetchedAt.isAfter(latest);
  }

  /** Logs a part the scraper couldn't get; true if it was one. */
  private static boolean reportFailure(String part, ScrapeResultRequest request, Object partBody) {
    ScrapeResultRequest.FailureReason reason;
    String detail;
    if (partBody instanceof FixturesPart fixtures && fixtures.status() == PartStatus.FAILED) {
      reason = fixtures.reason();
      detail = fixtures.detail();
    } else if (partBody instanceof StandingsPart standings
        && standings.status() == PartStatus.FAILED) {
      reason = standings.reason();
      detail = standings.detail();
    } else if (partBody instanceof PlayerStatsPart playerStats
        && playerStats.status() == PartStatus.FAILED) {
      reason = playerStats.reason();
      detail = playerStats.detail();
    } else if (partBody instanceof GameDetailsPart gameDetails
        && gameDetails.status() == PartStatus.FAILED) {
      reason = gameDetails.reason();
      detail = gameDetails.detail();
    } else {
      return false;
    }
    log.info(
        "Scraper reported {} failed ({}) for team {} league {} season {}",
        part,
        reason,
        request.teamId(),
        request.leagueId(),
        request.seasonId());
    log.debug("Scraper's detail for the failed {}: {}", part, detail);
    return true;
  }

  private static ScrapedFixtures toFixtures(FixturesPart part) {
    if (part == null) {
      return null;
    }
    return new ScrapedFixtures(
        part.fetchedAt(),
        part.games().stream()
            .map(
                game ->
                    new ScrapedFixtures.Game(
                        game.gameId(),
                        game.date(),
                        game.kickoffTime(),
                        game.homeTeamName(),
                        game.awayTeamName(),
                        game.venue(),
                        game.homeScore(),
                        game.awayScore()))
            .toList());
  }

  private static ScrapedStandings toStandings(StandingsPart part) {
    if (part == null) {
      return null;
    }
    return new ScrapedStandings(
        part.fetchedAt(),
        part.round(),
        part.rows().stream()
            .map(
                row ->
                    new ScrapedStandings.Row(
                        row.position(),
                        row.teamId(),
                        row.teamName(),
                        row.played(),
                        row.won(),
                        row.drawn(),
                        row.lost(),
                        row.goalsFor(),
                        row.goalsAgainst(),
                        row.points()))
            .toList());
  }

  private static ScrapedPlayerStats toPlayerStats(PlayerStatsPart part) {
    if (part == null) {
      return null;
    }
    return new ScrapedPlayerStats(
        part.fetchedAt(),
        part.players().stream()
            .map(
                row ->
                    new ScrapedPlayerStats.Row(
                        row.playerId(),
                        row.name(),
                        row.games(),
                        row.goals(),
                        row.yellowCardsLeagueAndCup(),
                        row.yellowCardsToto(),
                        row.redCards(),
                        row.starts(),
                        row.cameOnAsSub(),
                        row.subbedOff(),
                        row.minutes()))
            .toList());
  }

  private static ScrapedGameDetails toGameDetails(GameDetailsPart part) {
    if (part == null) {
      return null;
    }
    return new ScrapedGameDetails(
        part.fetchedAt(),
        part.games().stream()
            .map(
                game ->
                    new ScrapedGameDetails.Game(
                        game.gameId(),
                        game.date(),
                        game.kickoffTime(),
                        game.competition(),
                        game.round(),
                        game.ownSide(),
                        game.homeTeamName(),
                        game.awayTeamName(),
                        game.homeScore(),
                        game.awayScore(),
                        game.players().stream()
                            .map(
                                player ->
                                    new ScrapedGameDetails.Appearance(
                                        player.playerId(),
                                        player.name(),
                                        player.shirtNumber(),
                                        player.role(),
                                        player.goalkeeper(),
                                        player.captain(),
                                        player.events().stream()
                                            .map(
                                                event ->
                                                    new ScrapedGameDetails.Event(
                                                        event.kind(), event.minute()))
                                            .toList()))
                            .toList()))
            .toList(),
        part.failures().stream()
            .map(
                failure ->
                    new ScrapedGameDetails.Failure(
                        failure.gameId(), toMatchReason(failure.reason()), failure.detail()))
            .toList());
  }

  /**
   * Exhaustive, with no {@code default}: a reason added to the contract's enum doesn't compile
   * until {@code match} has it too ({@code FailureReasonMirrorTest} checks the other direction).
   */
  private static ScrapedGameDetails.FailureReason toMatchReason(
      ScrapeResultRequest.FailureReason reason) {
    return switch (reason) {
      case SOURCE_BLOCKED -> ScrapedGameDetails.FailureReason.SOURCE_BLOCKED;
      case TIMEOUT -> ScrapedGameDetails.FailureReason.TIMEOUT;
      case SOURCE_ERROR -> ScrapedGameDetails.FailureReason.SOURCE_ERROR;
      case PARSE_ERROR -> ScrapedGameDetails.FailureReason.PARSE_ERROR;
    };
  }

  private static ScrapeResultResponse.PartOutcome failureRecorded() {
    return new ScrapeResultResponse.PartOutcome(
        ScrapeResultResponse.Outcome.FAILURE_RECORDED, null);
  }

  private static ScrapeResultResponse.PartOutcome outcome(IngestResult.PartResult part) {
    return new ScrapeResultResponse.PartOutcome(
        ScrapeResultResponse.Outcome.valueOf(part.outcome().name()), part.reason());
  }
}
