// The scrape flow with player statistics (KAN-73). scrape.test.js, whose JOB has no playerStats,
// covers the flow without them and is unchanged.

import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createMemoryLogger } from "../src/log.js";
import { runScrape } from "../src/scrape.js";
import { BLOCK_HTML, GAMES_HTML, JOB_ALL, OK_SCRIPT, fakeClock, fakeSource } from "./helpers.js";

const GAMES = "/team-details/team-games/";
const STATS = "/Components.asmx/GetTeamPlayersStatisticsList";
const LEAGUE = "/leagues/league/";
const TABLE = "/Components.asmx/LeagueTable";
const STATS_URL =
  "https://www.football.org.il/Components.asmx/GetTeamPlayersStatisticsList?teamId=7617&seasonId=28&isFemale=%22False%22";
const NOT_ATTEMPTED_BLOCKED = {
  status: "FAILED",
  reason: "SOURCE_BLOCKED",
  detail: "not attempted: an earlier request was blocked",
};

async function scrape(job, script, extra = {}) {
  const clock = fakeClock();
  const source = fakeSource(clock, script);
  const log = createMemoryLogger();
  const payload = await runScrape(job, { source, clock, log, random: () => 0.5, ...extra });
  return { payload, source, clock, log, paths: source.requests.map((r) => r.path) };
}

describe("scrape orchestration with player statistics", () => {
  test("all three: 4 requests in order with the exact URLs; all OK", async () => {
    const { payload, source, clock } = await scrape(JOB_ALL, OK_SCRIPT());
    assert.deepEqual(
      source.requests.map((r) => [r.kind, r.url, r.timeoutMs]),
      [
        [
          "document",
          "https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28",
          30000,
        ],
        ["fetch", STATS_URL, 20000],
        [
          "document",
          "https://www.football.org.il/leagues/league/?league_id=62&season_id=28",
          30000,
        ],
        [
          "fetch",
          "https://www.football.org.il/Components.asmx/LeagueTable?league_id=62&season_id=28&box=20&round_id=6&componentTitle=%D7%98%D7%91%D7%9C%D7%AA%20%D7%9C%D7%99%D7%92%D7%94",
          20000,
        ],
      ],
    );
    assert.deepEqual(clock.sleeps, [4000, 4000, 4000]);
    assert.equal(source.closed, 1);
    assert.equal(payload.fixtures.status, "OK");
    assert.equal(payload.standings.status, "OK");
    assert.equal(payload.playerStats.status, "OK");
    assert.equal(payload.playerStats.players.length, 34);
    // The stats' own response time: 100 ms + 4 s pause + 100 ms.
    assert.equal(payload.playerStats.fetchedAt, "2026-10-11T09:00:04.200Z");
    assert.equal(payload.fixtures.fetchedAt, "2026-10-11T09:00:00.100Z");
    assert.equal(payload.standings.fetchedAt, "2026-10-11T09:00:12.400Z");
  });

  test("the stats URL: new URL(...).href percent-encodes the quoted isFemale", () => {
    const href = new URL(
      `https://www.football.org.il/Components.asmx/GetTeamPlayersStatisticsList?teamId=7617&seasonId=28&isFemale=${encodeURIComponent('"False"')}`,
    ).href;
    assert.equal(href, STATS_URL);
    assert.ok(href.endsWith("&isFemale=%22False%22"));
  });

  test("stats only: 2 requests; the games page is loaded but not parsed", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 200, body: "<html></html>" }] };
    const job = { ...JOB_ALL, fixtures: false, standings: false };
    const { payload, paths } = await scrape(job, script);
    assert.deepEqual(paths, [GAMES, STATS]);
    assert.equal(payload.fixtures, null);
    assert.equal(payload.standings, null);
    assert.equal(payload.playerStats.status, "OK");
  });

  test("stats and standings, no fixtures: 4 requests, fixtures null", async () => {
    const { payload, paths } = await scrape({ ...JOB_ALL, fixtures: false }, OK_SCRIPT());
    assert.deepEqual(paths, [GAMES, STATS, LEAGUE, TABLE]);
    assert.equal(payload.fixtures, null);
    assert.equal(payload.standings.status, "OK");
    assert.equal(payload.playerStats.status, "OK");
  });

  const teamPageFailures = {
    "a timeout (after its retry)": [
      [{ timeout: true }],
      [GAMES, GAMES, LEAGUE, TABLE],
      "TIMEOUT",
      "no answer from source within 30 s",
    ],
    "a 5xx (after its retry)": [
      [{ status: 502, body: "" }],
      [GAMES, GAMES, LEAGUE, TABLE],
      "SOURCE_ERROR",
      "502 from source",
    ],
    "a 404": [
      [{ status: 404, body: "" }],
      [GAMES, LEAGUE, TABLE],
      "SOURCE_ERROR",
      "404 from source",
    ],
    "a network error": [
      [{ network: true }],
      [GAMES, LEAGUE, TABLE],
      "SOURCE_ERROR",
      "network error",
    ],
  };
  for (const [name, [answers, expectedPaths, reason, detail]] of Object.entries(teamPageFailures)) {
    test(`team page ${name}: stats not attempted, same reason; standings still attempted`, async () => {
      const { payload, paths } = await scrape(JOB_ALL, { ...OK_SCRIPT(), [GAMES]: answers });
      assert.deepEqual(paths, expectedPaths);
      assert.deepEqual(payload.fixtures, { status: "FAILED", reason, detail });
      assert.deepEqual(payload.playerStats, {
        status: "FAILED",
        reason,
        detail: `not attempted: the team games page didn't load (${detail})`,
      });
      assert.equal(payload.standings.status, "OK");
    });
  }

  test("team page 200 but the fixtures don't parse: the stats are still fetched", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 200, body: "<html></html>" }] };
    const { payload, paths } = await scrape(JOB_ALL, script);
    assert.deepEqual(paths, [GAMES, STATS, LEAGUE, TABLE]);
    assert.equal(payload.fixtures.reason, "PARSE_ERROR");
    assert.equal(payload.playerStats.status, "OK");
    assert.equal(payload.standings.status, "OK");
  });

  test("a block on the team page: every part SOURCE_BLOCKED after 1 request", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 403, body: BLOCK_HTML }] };
    const { payload, paths } = await scrape(JOB_ALL, script);
    assert.deepEqual(paths, [GAMES]);
    assert.deepEqual(payload.fixtures, {
      status: "FAILED",
      reason: "SOURCE_BLOCKED",
      detail: "403 from source (blocked)",
    });
    assert.deepEqual(payload.playerStats, NOT_ATTEMPTED_BLOCKED);
    assert.deepEqual(payload.standings, NOT_ATTEMPTED_BLOCKED);
  });

  test("a block on the stats fetch: no further request, standings not attempted, fixtures OK", async () => {
    const script = { ...OK_SCRIPT(), [STATS]: [{ status: 403, body: BLOCK_HTML }] };
    const { payload, paths } = await scrape(JOB_ALL, script);
    assert.deepEqual(paths, [GAMES, STATS]);
    assert.equal(payload.fixtures.status, "OK");
    assert.deepEqual(payload.playerStats, {
      status: "FAILED",
      reason: "SOURCE_BLOCKED",
      detail: "403 from source (blocked)",
    });
    assert.deepEqual(payload.standings, NOT_ATTEMPTED_BLOCKED);
  });

  test("a stats timeout uses the scrape's one retry; a later standings timeout gets none", async () => {
    const script = { ...OK_SCRIPT(), [STATS]: [{ timeout: true }], [LEAGUE]: [{ timeout: true }] };
    const { payload, paths } = await scrape(JOB_ALL, script);
    assert.deepEqual(paths, [GAMES, STATS, STATS, LEAGUE]);
    assert.equal(payload.fixtures.status, "OK");
    assert.deepEqual(payload.playerStats, {
      status: "FAILED",
      reason: "TIMEOUT",
      detail: "no answer from source within 20 s",
    });
    assert.equal(payload.standings.reason, "TIMEOUT");
  });

  test("a stats 5xx: one retry, then SOURCE_ERROR; the other parts OK", async () => {
    const script = { ...OK_SCRIPT(), [STATS]: [{ status: 500, body: "" }] };
    const { payload, paths } = await scrape(JOB_ALL, script);
    assert.deepEqual(paths, [GAMES, STATS, STATS, LEAGUE, TABLE]);
    assert.equal(payload.playerStats.reason, "SOURCE_ERROR");
    assert.equal(payload.fixtures.status, "OK");
    assert.equal(payload.standings.status, "OK");
  });

  test("a stats parse error: PARSE_ERROR for the stats only", async () => {
    const script = { ...OK_SCRIPT(), [STATS]: [{ status: 200, body: "<ResponseData/>" }] };
    const { payload, paths } = await scrape(JOB_ALL, script);
    assert.deepEqual(paths, [GAMES, STATS, LEAGUE, TABLE]);
    assert.deepEqual(payload.playerStats, {
      status: "FAILED",
      reason: "PARSE_ERROR",
      detail: "no HtmlData in the GetTeamPlayersStatisticsList response",
    });
    assert.equal(payload.fixtures.status, "OK");
    assert.equal(payload.standings.status, "OK");
  });

  test("the 90 s cap reached before the stats: TIMEOUT", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 200, body: GAMES_HTML, ms: 87000 }] };
    const { payload, paths } = await scrape(JOB_ALL, script);
    assert.deepEqual(paths, [GAMES]);
    assert.equal(payload.fixtures.status, "OK");
    const capped = {
      status: "FAILED",
      reason: "TIMEOUT",
      detail: "overall scrape time limit reached",
    };
    assert.deepEqual(payload.playerStats, capped);
    assert.deepEqual(payload.standings, capped);
  });

  test("the browser fails to start: every requested part SOURCE_ERROR", async () => {
    const clock = fakeClock();
    const source = {
      origin: "https://www.football.org.il/",
      open: async () => {
        throw new Error("no chromium");
      },
    };
    const payload = await runScrape(JOB_ALL, { source, clock, log: createMemoryLogger() });
    const failure = { status: "FAILED", reason: "SOURCE_ERROR", detail: "browser failed to start" };
    assert.deepEqual(
      [payload.fixtures, payload.standings, payload.playerStats],
      [failure, failure, failure],
    );
  });

  test("a job without playerStats (a pre-KAN-73 trigger) never fetches them", async () => {
    const { payload, paths } = await scrape({ ...JOB_ALL, playerStats: undefined }, OK_SCRIPT());
    assert.deepEqual(paths, [GAMES, LEAGUE, TABLE]);
    assert.equal(payload.playerStats, null);
  });
});
