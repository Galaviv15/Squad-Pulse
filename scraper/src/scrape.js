// One scrape: at most 3 requests (+ one retry), paced, capped at 90 s, stopped at once on a block.
// Builds the result payload for the backend (docs/scraper-backend-contract.md).

import { isBlocked } from "./block.js";
import { realClock } from "./clock.js";
import { enforceContract, failedPart } from "./contract.js";
import { parseGamesPage } from "./parseFixtures.js";
import { parseLeaguePage, parseLeagueTable } from "./parseStandings.js";
import { SourceNetworkError, SourceTimeoutError } from "./sourceErrors.js";
import { ParseError } from "./text.js";

export const SCRAPE_DEFAULTS = {
  capMs: 90_000,
  pauseMinMs: 3_000,
  pauseMaxMs: 5_000,
  retryDelayMs: 10_000,
  navigationTimeoutMs: 30_000,
  fetchTimeoutMs: 20_000,
};

const NOT_ATTEMPTED_BLOCKED = "not attempted: an earlier request was blocked";

/**
 * @param job {runId, teamId, leagueId, seasonId, fixtures, standings}, already validated
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

  async function scrapeFixtures() {
    const url = `${origin}team-details/team-games/?${ids({ team_id: job.teamId, season_id: job.seasonId })}`;
    const { response, failure } = await request("document", url, options.navigationTimeoutMs);
    if (failure) return failure;
    const at = fetchedAt();
    return parsed(() => ({ status: "OK", fetchedAt: at, games: parseGamesPage(response.body) }));
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

  const payload = {
    runId: job.runId,
    teamId: job.teamId,
    leagueId: job.leagueId,
    seasonId: job.seasonId,
    fixtures: null,
    standings: null,
  };

  try {
    session = await source.open(signal);
  } catch (error) {
    signal?.throwIfAborted();
    log.error(runId, "browser failed to start", { error: error?.name });
    const failure = failedPart("SOURCE_ERROR", "browser failed to start");
    if (job.fixtures) payload.fixtures = failure;
    if (job.standings) payload.standings = failure;
    return enforceContract(payload);
  }
  try {
    if (job.fixtures) payload.fixtures = await scrapeFixtures();
    if (job.standings) {
      payload.standings = blocked
        ? failedPart("SOURCE_BLOCKED", NOT_ATTEMPTED_BLOCKED)
        : await scrapeStandings();
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
