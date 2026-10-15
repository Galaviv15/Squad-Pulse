// One scrape: at most 6 requests (+ one retry), paced, capped at 90 s, stopped at once on a block.
// Builds the result payload for the backend (docs/scraper-backend-contract.md).
//
// Order: team games page → player statistics (an in-page fetch from that page) → league page →
// LeagueTable (KAN-70 findings section 3) → up to 2 game pages (KAN-75 findings section 6: last, so
// a slow site loses them to the cap before anything else). The team games page is loaded if
// `fixtures`, `playerStats` or `gameDetails` is requested and parsed once; the stats are fetched
// only from a page that loaded, never by navigating again, and the game pages are picked from its
// rows only.

import { isBlocked } from "./block.js";
import { realClock } from "./clock.js";
import { enforceContract, failedPart, gameFailure } from "./contract.js";
import { parseGamesPage } from "./parseFixtures.js";
import { parseGameDetails } from "./parseGameDetails.js";
import { parsePlayerStats } from "./parsePlayerStats.js";
import { parseLeaguePage, parseLeagueTable } from "./parseStandings.js";
import { selectGames } from "./selectGames.js";
import { SourceNetworkError, SourceTimeoutError } from "./sourceErrors.js";
import { ParseError } from "./text.js";

export const SCRAPE_DEFAULTS = {
  capMs: 90_000,
  pauseMinMs: 3_000,
  pauseMaxMs: 5_000,
  retryDelayMs: 10_000,
  navigationTimeoutMs: 30_000,
  fetchTimeoutMs: 20_000,
  // A game page is started only if at least this much of the cap is left (KAN-75 findings §6).
  gameMinRemainingMs: 15_000,
};

const NOT_ATTEMPTED_BLOCKED = "not attempted: an earlier request was blocked";

/**
 * @param job {runId, teamId, leagueId, seasonId, fixtures, standings, playerStats?, gameDetails?,
 *   knownGameIds?}, already validated; a missing `playerStats` / `gameDetails` means `false`, a
 *   missing `knownGameIds` `[]`
 * @param deps {source, log, clock?, random?, signal?, ...SCRAPE_DEFAULTS overrides}
 */
export async function runScrape(job, deps) {
  const options = { ...SCRAPE_DEFAULTS, ...deps };
  const { source, log, clock = realClock, random = Math.random, signal } = options;
  const { runId } = job;
  const deadline = clock.now() + options.capMs;
  const origin = source.origin;
  const fetchedAt = () => new Date(clock.now()).toISOString();

  let requestsSent = 0;
  let retriesLeft = 1;
  let blocked = false;
  let session = null;
  // Once the team games page loaded: {rows} or, if it didn't parse, {error} (the ParseError message).
  let teamGames = null;

  const timeLimit = (detail = "overall scrape time limit reached") => ({
    failure: failedPart("TIMEOUT", detail),
  });

  /** Sends one request: the pause before it, then at most one retry. → {response} | {failure}. */
  async function request(kind, url, timeoutMs) {
    const path = new URL(url).pathname;
    let failure = null;
    for (let attempt = 0; ; attempt += 1) {
      // A retry waits retryDelayMs instead of the usual 3–5 s pause; the first request waits for nothing.
      const wait =
        attempt > 0
          ? options.retryDelayMs
          : requestsSent > 0
            ? options.pauseMinMs + random() * (options.pauseMaxMs - options.pauseMinMs)
            : 0;
      if (wait > 0) {
        if (clock.now() + wait >= deadline) return failure ? { failure } : timeLimit();
        await clock.sleep(wait, signal);
      }
      const remaining = deadline - clock.now();
      if (remaining <= 0) return timeLimit();
      const effectiveTimeout = Math.min(timeoutMs, remaining);
      const started = clock.now();
      requestsSent += 1;
      let retryable;
      try {
        const response =
          kind === "document"
            ? await session.navigate(url, effectiveTimeout)
            : await session.fetchInPage(url, effectiveTimeout);
        signal?.throwIfAborted();
        const ms = Math.round(clock.now() - started);
        log.info(runId, "source request", { path, status: response.status, ms });
        if (isBlocked(response.status, response.body)) {
          blocked = true;
          log.warn(runId, "source blocked", { path, status: response.status });
          return {
            failure: failedPart("SOURCE_BLOCKED", `${response.status} from source (blocked)`),
          };
        }
        if (response.status < 400) return { response };
        retryable = response.status >= 500;
        failure = failedPart("SOURCE_ERROR", `${response.status} from source`);
      } catch (error) {
        signal?.throwIfAborted();
        const ms = Math.round(clock.now() - started);
        if (error instanceof SourceTimeoutError) {
          log.info(runId, "source request", { path, status: "timeout", ms });
          retryable = true;
          failure =
            effectiveTimeout < timeoutMs
              ? timeLimit().failure
              : failedPart("TIMEOUT", `no answer from source within ${timeoutMs / 1000} s`);
        } else if (error instanceof SourceNetworkError) {
          log.info(runId, "source request", { path, status: "network-error", ms });
          retryable = false;
          failure = failedPart("SOURCE_ERROR", "network error");
        } else {
          throw error;
        }
      }
      if (!retryable || attempt > 0 || retriesLeft === 0) return { failure };
      retriesLeft -= 1;
      log.info(runId, "retrying source request", { path });
    }
  }

  const ids = (params) => new URLSearchParams(params).toString();

  /**
   * The team games page, then (from that page) the stats. Sets `fixtures` and `playerStats`, and
   * `gameDetails` if the page didn't load; parses the page once, into `teamGames`.
   */
  async function scrapeTeamParts() {
    const url = `${origin}team-details/team-games/?${ids({ team_id: job.teamId, season_id: job.seasonId })}`;
    const { response, failure } = await request("document", url, options.navigationTimeoutMs);
    if (failure) {
      if (job.fixtures) payload.fixtures = failure;
      const notAttempted = blocked
        ? failedPart("SOURCE_BLOCKED", NOT_ATTEMPTED_BLOCKED)
        : failedPart(
            failure.reason,
            `not attempted: the team games page didn't load (${failure.detail ?? failure.reason})`,
          );
      if (job.playerStats) payload.playerStats = notAttempted;
      if (job.gameDetails) payload.gameDetails = notAttempted;
      return;
    }
    const at = fetchedAt();
    try {
      teamGames = { rows: parseGamesPage(response.body) };
    } catch (error) {
      if (!(error instanceof ParseError)) throw error;
      teamGames = { error: error.message };
    }
    if (job.fixtures) {
      payload.fixtures = teamGames.rows
        ? { status: "OK", fetchedAt: at, games: teamGames.rows }
        : failedPart("PARSE_ERROR", teamGames.error);
    }
    // A fixtures parse failure doesn't stop the stats: the page is loaded, and that's all they need.
    if (job.playerStats) payload.playerStats = await scrapePlayerStats();
  }

  async function scrapePlayerStats() {
    // camelCase ids, unlike LeagueTable; isFemale is the JSON string "False": men's teams only.
    // Serialized by URL so it equals, character for character, the URL the browser reports.
    const url = new URL(
      `${origin}Components.asmx/GetTeamPlayersStatisticsList?teamId=${job.teamId}&seasonId=${job.seasonId}&isFemale=${encodeURIComponent('"False"')}`,
    ).href;
    const stats = await request("fetch", url, options.fetchTimeoutMs);
    if (stats.failure) return stats.failure;
    const at = fetchedAt();
    return parsed(() => ({
      status: "OK",
      fetchedAt: at,
      players: parsePlayerStats(stats.response.body),
    }));
  }

  async function scrapeStandings() {
    const leagueUrl = `${origin}leagues/league/?${ids({ league_id: job.leagueId, season_id: job.seasonId })}`;
    const league = await request("document", leagueUrl, options.navigationTimeoutMs);
    if (league.failure) return league.failure;
    let placeholder;
    try {
      placeholder = parseLeaguePage(league.response.body);
    } catch (error) {
      if (error instanceof ParseError) return failedPart("PARSE_ERROR", error.message);
      throw error;
    }
    const query = ids({
      league_id: job.leagueId,
      season_id: job.seasonId,
      box: placeholder.box,
      round_id: placeholder.round,
    });
    // Serialized by URL so it equals, character for character, the URL the browser reports.
    const tableUrl = new URL(
      `${origin}Components.asmx/LeagueTable?${query}&componentTitle=${encodeURIComponent(placeholder.title)}`,
    ).href;
    const table = await request("fetch", tableUrl, options.fetchTimeoutMs);
    if (table.failure) return table.failure;
    const at = fetchedAt();
    return parsed(() => ({
      status: "OK",
      fetchedAt: at,
      round: placeholder.round,
      rows: parseLeagueTable(table.response.body),
    }));
  }

  /**
   * Up to 2 game pages picked from the team games rows (`selectGames`), our side of each parsed.
   * → an `OK` part with `games` and `failures` (a game not started is in neither), or `FAILED` if
   * the team games page didn't parse. Never logs a name: game ids, counts and reasons only.
   */
  async function scrapeGameDetails() {
    if (teamGames.error !== undefined) {
      return failedPart(
        "PARSE_ERROR",
        `not attempted: the team games page didn't parse (${teamGames.error})`,
      );
    }
    const selected = selectGames(teamGames.rows, job.knownGameIds ?? [], clock.now());
    log.info(runId, "game details selected", {
      count: selected.length,
      gameIds: selected.map((row) => row.gameId).join(",") || "-",
    });
    const games = [];
    const failures = [];
    for (const [i, row] of selected.entries()) {
      const { gameId } = row;
      if (deadline - clock.now() < options.gameMinRemainingMs) {
        for (const skipped of selected.slice(i)) {
          log.info(runId, "game details not attempted: time limit", { gameId: skipped.gameId });
        }
        break;
      }
      const url = `${origin}leagues/games/game/?${ids({ game_id: gameId })}`;
      const { response, failure } = await request("document", url, options.navigationTimeoutMs);
      if (failure) {
        failures.push(gameFailure(gameId, failure.reason, failure.detail));
        log.info(runId, "game details failed", { gameId, reason: failure.reason });
        if (blocked) {
          for (const skipped of selected.slice(i + 1)) {
            log.info(runId, "game details not attempted: blocked", { gameId: skipped.gameId });
          }
          break;
        }
        continue;
      }
      try {
        const { game, warnings } = parseGameDetails(response.body, {
          gameId,
          teamId: job.teamId,
          row,
        });
        for (const { check, expected, actual } of warnings) {
          log.warn(runId, "game details check", { gameId, check, expected, actual });
        }
        games.push(game);
      } catch (error) {
        if (!(error instanceof ParseError)) throw error;
        failures.push(gameFailure(gameId, "PARSE_ERROR", error.message));
        log.info(runId, "game details failed", { gameId, reason: "PARSE_ERROR" });
      }
    }
    return { status: "OK", fetchedAt: fetchedAt(), games, failures };
  }

  const payload = {
    runId: job.runId,
    teamId: job.teamId,
    leagueId: job.leagueId,
    seasonId: job.seasonId,
    fixtures: null,
    standings: null,
    playerStats: null,
    gameDetails: null,
  };

  try {
    session = await source.open(signal);
  } catch (error) {
    signal?.throwIfAborted();
    log.error(runId, "browser failed to start", { error: error?.name });
    const failure = failedPart("SOURCE_ERROR", "browser failed to start");
    if (job.fixtures) payload.fixtures = failure;
    if (job.standings) payload.standings = failure;
    if (job.playerStats) payload.playerStats = failure;
    if (job.gameDetails) payload.gameDetails = failure;
    return enforceContract(payload);
  }
  try {
    if (job.fixtures || job.playerStats || job.gameDetails) await scrapeTeamParts();
    if (job.standings) {
      payload.standings = blocked
        ? failedPart("SOURCE_BLOCKED", NOT_ATTEMPTED_BLOCKED)
        : await scrapeStandings();
    }
    // Still null only if the team games page loaded (otherwise it's FAILED "not attempted" above).
    if (job.gameDetails && payload.gameDetails === null) {
      payload.gameDetails = blocked
        ? failedPart("SOURCE_BLOCKED", NOT_ATTEMPTED_BLOCKED)
        : await scrapeGameDetails();
    }
  } finally {
    await session.close().catch(() => {});
  }
  signal?.throwIfAborted();
  return enforceContract(payload);
}

function parsed(build) {
  try {
    return build();
  } catch (error) {
    if (error instanceof ParseError) return failedPart("PARSE_ERROR", error.message);
    throw error;
  }
}
