package com.squadpulse.scrapingintegration;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;
import com.squadpulse.common.NoControlCharacters;
import com.squadpulse.match.FixturesResponse;
import com.squadpulse.match.ScrapedGameDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The body of {@code POST /internal/scrape-results}: one scrape of one team's league fixtures, its
 * league's standings, the team's player season statistics (KAN-72) and the details of some of its
 * played games (KAN-79), posted by the scraper (KAN-66). <b>These records are the scraper ↔ backend
 * contract</b>, and docs/scraper-backend-contract.md is its public description: keep the two in
 * sync.
 *
 * <p>No string in it may contain a control character ({@link NoControlCharacters}): the backend
 * joins team names with one to build a game's key.
 *
 * <p>Validated as a whole before anything is written (Bean Validation, plus the {@code fetchedAt}
 * check in {@link ScrapeResultController}); unknown properties anywhere in it are rejected ({@link
 * ScrapeResultJsonConfig}). There is deliberately no clubId: the backend finds the clubs from the
 * three ids (CLAUDE.md standing rule 4).
 *
 * @param runId the trigger's runId, echoed: it names the run whose status this result sets (KAN-68,
 *     {@link ScrapeRunStatusService#recordResult}); {@code null} or unknown sets none
 * @param fixtures {@code null} / absent if not attempted
 * @param standings {@code null} / absent if not attempted
 * @param playerStats {@code null} / absent if not attempted (KAN-72)
 * @param gameDetails {@code null} / absent if not attempted (KAN-79)
 */
public record ScrapeResultRequest(
    @Size(max = 100) @NoControlCharacters String runId,
    @NotNull @Positive Integer teamId,
    @NotNull @Positive Integer leagueId,
    @NotNull @Positive Integer seasonId,
    @Valid FixturesPart fixtures,
    @Valid StandingsPart standings,
    @Valid PlayerStatsPart playerStats,
    @Valid GameDetailsPart gameDetails) {

  @AssertTrue(message = "fixtures, standings, playerStats or gameDetails must be present")
  boolean isAnyPartPresent() {
    return fixtures != null || standings != null || playerStats != null || gameDetails != null;
  }

  /** Whether a part succeeded; {@code FAILED} carries a {@link FailureReason} instead of data. */
  public enum PartStatus {
    OK,
    FAILED
  }

  /** Why the scraper couldn't get a part. */
  public enum FailureReason {
    /** The site refused the request (e.g. bot protection). */
    SOURCE_BLOCKED,
    TIMEOUT,
    /** The site answered with an error. */
    SOURCE_ERROR,
    /** The page didn't have the expected shape. */
    PARSE_ERROR
  }

  /**
   * {@code OK}: {@code fetchedAt} and {@code games}, no {@code reason} / {@code detail}. {@code
   * FAILED}: {@code reason} (and optionally {@code detail}), no {@code fetchedAt} / {@code games}.
   *
   * @param detail free text for the scraper's operator; never logged above DEBUG
   */
  public record FixturesPart(
      @NotNull PartStatus status,
      Instant fetchedAt,
      @Size(min = 1, max = 100) List<@NotNull @Valid Game> games,
      FailureReason reason,
      @Size(max = 500) @NoControlCharacters String detail) {

    @AssertTrue(
        message =
            "status OK needs fetchedAt and games and no reason or detail; FAILED needs a reason"
                + " and no fetchedAt or games")
    boolean isConsistentWithStatus() {
      return isConsistent(
          status,
          fetchedAt != null && games != null,
          fetchedAt != null || games != null,
          reason,
          detail);
    }
  }

  /**
   * {@code OK}: {@code fetchedAt}, {@code round} and {@code rows}, no {@code reason} / {@code
   * detail}. {@code FAILED}: as for {@link FixturesPart}.
   *
   * @param round the round the scraper asked the site for
   */
  public record StandingsPart(
      @NotNull PartStatus status,
      Instant fetchedAt,
      @Positive Integer round,
      @Size(min = 2, max = 40) List<@NotNull @Valid StandingsRow> rows,
      FailureReason reason,
      @Size(max = 500) @NoControlCharacters String detail) {

    @AssertTrue(
        message =
            "status OK needs fetchedAt, round and rows and no reason or detail; FAILED needs a"
                + " reason and no fetchedAt, round or rows")
    boolean isConsistentWithStatus() {
      return isConsistent(
          status,
          fetchedAt != null && round != null && rows != null,
          fetchedAt != null || round != null || rows != null,
          reason,
          detail);
    }

    @AssertTrue(message = "positions must be 1..row count, and team ids and names unique")
    boolean isTableConsistent() {
      if (rows == null || rows.stream().anyMatch(Objects::isNull)) {
        return true;
      }
      Set<Integer> ids = new HashSet<>();
      Set<String> names = new HashSet<>();
      return rows.stream()
              .allMatch(
                  row ->
                      row.position() == null
                          || row.position() >= 1 && row.position() <= rows.size())
          && rows.stream().allMatch(row -> row.teamId() == null || ids.add(row.teamId()))
          && rows.stream().allMatch(row -> row.teamName() == null || names.add(row.teamName()));
    }
  }

  /**
   * The team's player season statistics (KAN-72): one row per registered player, all competitions
   * together, in the page's order. {@code OK}: {@code fetchedAt} and {@code players}, no {@code
   * reason} / {@code detail}. {@code FAILED}: as for {@link FixturesPart}.
   *
   * <p>Besides each row's own checks, {@code playerId} must be unique in the list. Nothing else is
   * checked across rows, and minutes are never checked against games (added and extra time).
   */
  public record PlayerStatsPart(
      @NotNull PartStatus status,
      Instant fetchedAt,
      @Size(min = 1, max = 80) List<@NotNull @Valid PlayerStatsRow> players,
      FailureReason reason,
      @Size(max = 500) @NoControlCharacters String detail) {

    @AssertTrue(
        message =
            "status OK needs fetchedAt and players and no reason or detail; FAILED needs a reason"
                + " and no fetchedAt or players")
    boolean isConsistentWithStatus() {
      return isConsistent(
          status,
          fetchedAt != null && players != null,
          fetchedAt != null || players != null,
          reason,
          detail);
    }

    @AssertTrue(message = "playerId must be unique")
    boolean isPlayerIdsUnique() {
      if (players == null) {
        return true;
      }
      Set<Integer> ids = new HashSet<>();
      return players.stream()
          .filter(Objects::nonNull)
          .allMatch(row -> row.playerId() == null || ids.add(row.playerId()));
    }
  }

  /**
   * One player's season totals, as the site publishes them (all competitions together).
   *
   * @param playerId the site's {@code player_id}; never leaves the backend
   * @param name as the site shows it (surname first, 1–3 given names); trimmed here
   * @param yellowCardsLeagueAndCup yellow cards in the league and the State Cup
   * @param yellowCardsToto yellow cards in the Toto Cup
   * @param games {@code starts + cameOnAsSub}, the one cross-field check
   */
  public record PlayerStatsRow(
      @NotNull @Positive Integer playerId,
      @NotBlank @Size(max = 100) @NoControlCharacters String name,
      @NotNull @Min(0) @Max(200) Integer games,
      @NotNull @Min(0) @Max(200) Integer goals,
      @NotNull @Min(0) @Max(200) Integer yellowCardsLeagueAndCup,
      @NotNull @Min(0) @Max(200) Integer yellowCardsToto,
      @NotNull @Min(0) @Max(200) Integer redCards,
      @NotNull @Min(0) @Max(200) Integer starts,
      @NotNull @Min(0) @Max(200) Integer cameOnAsSub,
      @NotNull @Min(0) @Max(200) Integer subbedOff,
      @NotNull @Min(0) @Max(20000) Integer minutes) {

    public PlayerStatsRow {
      name = trim(name);
    }

    @AssertTrue(message = "starts + cameOnAsSub must equal games")
    boolean isAppearancesConsistent() {
      return games == null
          || starts == null
          || cameOnAsSub == null
          || starts + cameOnAsSub == games;
    }
  }

  /**
   * The details of the games the scraper fetched (KAN-79), <b>our side only</b>, and the games it
   * requested and couldn't read. {@code OK}: {@code fetchedAt}, {@code games} and {@code failures}
   * (each may be empty: nothing to fetch, or every attempt failed), no {@code reason} / {@code
   * detail}. {@code FAILED}: as for {@link FixturesPart}.
   *
   * <p>A {@code gameId} appears at most once across {@code games} and {@code failures} together.
   * Nothing else is checked across fields: not starters = 11, not one goalkeeper, not goals =
   * score, not subs on = subs off, not events against the role — real pages break each of them.
   */
  public record GameDetailsPart(
      @NotNull PartStatus status,
      Instant fetchedAt,
      @Size(max = 10) List<@NotNull @Valid GameDetailsGame> games,
      @Size(max = 10) List<@NotNull @Valid GameDetailsFailure> failures,
      FailureReason reason,
      @Size(max = 500) @NoControlCharacters String detail) {

    @AssertTrue(
        message =
            "status OK needs fetchedAt, games and failures and no reason or detail; FAILED needs a"
                + " reason and no fetchedAt, games or failures")
    boolean isConsistentWithStatus() {
      return isConsistent(
          status,
          fetchedAt != null && games != null && failures != null,
          fetchedAt != null || games != null || failures != null,
          reason,
          detail);
    }

    @AssertTrue(message = "gameId must be unique across games and failures")
    boolean isGameIdsUnique() {
      Set<Integer> ids = new HashSet<>();
      boolean unique = true;
      if (games != null) {
        unique =
            games.stream()
                .filter(Objects::nonNull)
                .allMatch(game -> game.gameId() == null || ids.add(game.gameId()));
      }
      if (unique && failures != null) {
        unique =
            failures.stream()
                .filter(Objects::nonNull)
                .allMatch(failure -> failure.gameId() == null || ids.add(failure.gameId()));
      }
      return unique;
    }
  }

  /**
   * One played game's details, our side only. Every field is required except {@code kickoffTime}.
   *
   * @param gameId the site's {@code game_id}; never leaves the backend
   * @param date from the team-games row, Israel local, {@code yyyy-MM-dd}
   * @param kickoffTime from the row, {@code HH:mm}; {@code null} if none
   * @param competition the page's {@code h1} before {@code " מחזור N"}; trimmed here
   * @param round {@code N} from the {@code h1}
   * @param ownSide our side on the page (picked by the header's {@code team_id})
   * @param homeTeamName short name, as the page shows it; trimmed here
   * @param homeScore home first
   * @param players our side only; {@code playerId} unique in the list
   */
  public record GameDetailsGame(
      @NotNull @Positive Integer gameId,
      @NotNull @JsonFormat(pattern = "uuuu-MM-dd", lenient = OptBoolean.FALSE) LocalDate date,
      @JsonFormat(pattern = "HH:mm", lenient = OptBoolean.FALSE) LocalTime kickoffTime,
      @NotBlank @Size(max = 100) @NoControlCharacters String competition,
      @NotNull @Min(1) @Max(99) Integer round,
      @NotNull FixturesResponse.Side ownSide,
      @NotBlank @Size(max = 100) @NoControlCharacters String homeTeamName,
      @NotBlank @Size(max = 100) @NoControlCharacters String awayTeamName,
      @NotNull @Min(0) @Max(99) Integer homeScore,
      @NotNull @Min(0) @Max(99) Integer awayScore,
      @NotNull @Size(min = 1, max = 40) List<@NotNull @Valid GameAppearance> players) {

    public GameDetailsGame {
      competition = trim(competition);
      homeTeamName = trim(homeTeamName);
      awayTeamName = trim(awayTeamName);
    }

    @AssertTrue(message = "homeTeamName and awayTeamName must differ")
    boolean isTwoTeams() {
      return homeTeamName == null || !homeTeamName.equals(awayTeamName);
    }

    @AssertTrue(message = "playerId must be unique")
    boolean isPlayerIdsUnique() {
      if (players == null) {
        return true;
      }
      Set<Integer> ids = new HashSet<>();
      return players.stream()
          .filter(Objects::nonNull)
          .allMatch(player -> player.playerId() == null || ids.add(player.playerId()));
    }
  }

  /**
   * One of our players in one game.
   *
   * @param playerId the site's {@code player_id} (the same as the statistics'); never leaves the
   *     backend
   * @param name first name first; trimmed here; {@code null} if the site masks it. Never logged.
   * @param shirtNumber the number worn in this game; {@code null} if missing
   * @param role {@code SUBSTITUTE} = came on
   * @param events any role may have any event
   */
  public record GameAppearance(
      @NotNull @Positive Integer playerId,
      @Size(min = 1, max = 100) @NoControlCharacters String name,
      @Min(0) @Max(99) Integer shirtNumber,
      @NotNull ScrapedGameDetails.Role role,
      @NotNull Boolean goalkeeper,
      @NotNull Boolean captain,
      @NotNull @Size(max = 10) List<@NotNull @Valid GameEvent> events) {

    public GameAppearance {
      name = trim(name);
    }
  }

  /**
   * @param minute as the site shows it (93, not 90+3); {@code null} if unknown (the site's {@code
   *     -1})
   */
  public record GameEvent(
      @NotNull ScrapedGameDetails.EventKind kind, @Min(0) @Max(150) Integer minute) {}

  /**
   * A game the scraper requested and couldn't read. A game it never started (out of time, after a
   * block) isn't listed.
   *
   * @param detail free text for the operator; never logged above DEBUG
   */
  public record GameDetailsFailure(
      @NotNull @Positive Integer gameId,
      @NotNull FailureReason reason,
      @Size(max = 500) @NoControlCharacters String detail) {}

  private static boolean isConsistent(
      PartStatus status,
      boolean okDataComplete,
      boolean anyOkData,
      FailureReason reason,
      String detail) {
    if (status == null) {
      return true; // reported by @NotNull
    }
    return switch (status) {
      case OK -> okDataComplete && reason == null && detail == null;
      case FAILED -> reason != null && !anyOkData;
    };
  }

  /**
   * One league game, home / away. The site shows the result away-first: the scraper swaps it before
   * posting.
   *
   * @param gameId the site's id, {@code null} for an unscheduled game (the site's {@code -1})
   * @param date Israel local date, {@code yyyy-MM-dd}
   * @param kickoffTime Israel local time, {@code HH:mm}; {@code null} while not set (the site's
   *     {@code 00:00})
   * @param homeTeamName as the site shows it; trimmed here
   * @param venue {@code null} when the site shows none; trimmed here
   * @param homeScore both scores or neither (not played)
   */
  public record Game(
      @Positive Integer gameId,
      @NotNull @JsonFormat(pattern = "uuuu-MM-dd", lenient = OptBoolean.FALSE) LocalDate date,
      @JsonFormat(pattern = "HH:mm", lenient = OptBoolean.FALSE) LocalTime kickoffTime,
      @NotBlank @Size(max = 100) @NoControlCharacters String homeTeamName,
      @NotBlank @Size(max = 100) @NoControlCharacters String awayTeamName,
      @Size(min = 1, max = 200) @NoControlCharacters String venue,
      @Min(0) @Max(99) Integer homeScore,
      @Min(0) @Max(99) Integer awayScore) {

    public Game {
      homeTeamName = trim(homeTeamName);
      awayTeamName = trim(awayTeamName);
      venue = trim(venue);
    }

    @AssertTrue(message = "homeScore and awayScore must both be set or both be null")
    boolean isScoreComplete() {
      return (homeScore == null) == (awayScore == null);
    }

    @AssertTrue(message = "homeTeamName and awayTeamName must differ")
    boolean isTwoTeams() {
      return homeTeamName == null || !homeTeamName.equals(awayTeamName);
    }
  }

  /**
   * One row of the league table. Goals are for / against: the site shows against-for, and the
   * scraper swaps them before posting. {@code points} as published, never recomputed.
   *
   * @param position 1..row count
   * @param teamId unique in the table
   * @param teamName unique in the table; trimmed here
   */
  public record StandingsRow(
      @NotNull @Positive Integer position,
      @NotNull @Positive Integer teamId,
      @NotBlank @Size(max = 100) @NoControlCharacters String teamName,
      @NotNull @Min(0) @Max(99) Integer played,
      @NotNull @Min(0) @Max(99) Integer won,
      @NotNull @Min(0) @Max(99) Integer drawn,
      @NotNull @Min(0) @Max(99) Integer lost,
      @NotNull @Min(0) @Max(999) Integer goalsFor,
      @NotNull @Min(0) @Max(999) Integer goalsAgainst,
      @NotNull @Min(-99) @Max(999) Integer points) {

    public StandingsRow {
      teamName = trim(teamName);
    }

    @AssertTrue(message = "won + drawn + lost must equal played")
    boolean isRecordConsistent() {
      return played == null
          || won == null
          || drawn == null
          || lost == null
          || won + drawn + lost == played;
    }
  }

  /**
   * Strips leading and trailing space characters ({@link Character#isSpaceChar}: spaces, incl.
   * no-break spaces) only — not {@link String#trim()}, which would also drop control characters at
   * either end, so {@link NoControlCharacters} would never see them.
   */
  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    int start = 0;
    int end = value.length();
    while (start < end && Character.isSpaceChar(value.charAt(start))) {
      start++;
    }
    while (end > start && Character.isSpaceChar(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }
}
