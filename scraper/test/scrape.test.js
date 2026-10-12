import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createMemoryLogger } from "../src/log.js";
import { runScrape } from "../src/scrape.js";
import {
  BLOCK_HTML,
  GAMES_HTML,
  JOB,
  OK_SCRIPT,
  TABLE_XML,
  fakeClock,
  fakeSource,
  leaguePage,
} from "./helpers.js";

const GAMES = "/team-details/team-games/";
const LEAGUE = "/leagues/league/";
const TABLE = "/Components.asmx/LeagueTable";

async function scrape(job, script, extra = {}) {
  const clock = fakeClock();
  const source = fakeSource(clock, script);
  const log = createMemoryLogger();
  const payload = await runScrape(job, { source, clock, log, random: () => 0.5, ...extra });
  return { payload, source, clock, log, paths: source.requests.map((r) => r.path) };
}

describe("scrape orchestration", () => {
  test("fixtures only: 1 request", async () => {
    const { payload, paths } = await scrape({ ...JOB, standings: false }, OK_SCRIPT());
    assert.deepEqual(paths, [GAMES]);
    assert.equal(payload.fixtures.status, "OK");
    assert.equal(payload.standings, null);
  });

  test("standings only: 2 requests", async () => {
    const { payload, paths } = await scrape({ ...JOB, fixtures: false }, OK_SCRIPT());
    assert.deepEqual(paths, [LEAGUE, TABLE]);
    assert.equal(payload.fixtures, null);
    assert.equal(payload.standings.status, "OK");
  });

  test("both: 3 requests, in order, with the exact URLs; the browser is closed", async () => {
    const { payload, source } = await scrape(JOB, OK_SCRIPT());
    assert.deepEqual(
      source.requests.map((r) => [r.kind, r.url, r.timeoutMs]),
      [
        [
          "document",
          "https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28",
          30000,
        ],
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
    assert.equal(source.closed, 1);
    assert.equal(payload.runId, "run-1");
    assert.deepEqual([payload.teamId, payload.leagueId, payload.seasonId], [7617, 62, 28]);
  });

  test("a 3–5 s pause between consecutive requests, none before the first", async () => {
    const low = await scrape(JOB, OK_SCRIPT(), { random: () => 0 });
    assert.deepEqual(low.clock.sleeps, [3000, 3000]);
    const high = await scrape(JOB, OK_SCRIPT(), { random: () => 0.999 });
    for (const ms of high.clock.sleeps) assert.ok(ms > 4990 && ms < 5000);
  });

  test("fetchedAt is each part's last response time, as an ISO instant with Z", async () => {
    const { payload } = await scrape(JOB, OK_SCRIPT());
    // Starts 09:00:00; each fake response takes 100 ms; pauses are 4 s.
    assert.equal(payload.fixtures.fetchedAt, "2026-10-11T09:00:00.100Z");
    assert.equal(payload.standings.fetchedAt, "2026-10-11T09:00:08.300Z");
  });

  test("a block on the games page: both parts SOURCE_BLOCKED, no further request, no retry, WARN", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 403, body: BLOCK_HTML }] };
    const { payload, paths, log, clock } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES]);
    assert.deepEqual(clock.sleeps, []);
    assert.equal(payload.fixtures.reason, "SOURCE_BLOCKED");
    assert.deepEqual(payload.standings, {
      status: "FAILED",
      reason: "SOURCE_BLOCKED",
      detail: "not attempted: an earlier request was blocked",
    });
    assert.ok(
      log.lines.some((l) =>
        / WARN run=run-1 source blocked path=\/team-details\/team-games\/ status=403$/.test(l),
      ),
    );
  });

  test("the block page with status 200 is a block too", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 200, body: BLOCK_HTML }] };
    const { payload, paths } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES]);
    assert.equal(payload.fixtures.reason, "SOURCE_BLOCKED");
  });

  test("a block on LeagueTable fails the standings only; fixtures stay OK", async () => {
    const script = { ...OK_SCRIPT(), [TABLE]: [{ status: 403, body: BLOCK_HTML }] };
    const { payload, paths } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES, LEAGUE, TABLE]);
    assert.equal(payload.fixtures.status, "OK");
    assert.equal(payload.standings.reason, "SOURCE_BLOCKED");
  });

  test("a timeout: one retry after 10 s, then TIMEOUT", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ timeout: true }] };
    const { payload, paths, clock } = await scrape({ ...JOB, standings: false }, script);
    assert.deepEqual(paths, [GAMES, GAMES]);
    assert.deepEqual(clock.sleeps, [10000]);
    assert.equal(payload.fixtures.reason, "TIMEOUT");
  });

  test("a timeout then a success: the retry's data is used", async () => {
    const script = {
      ...OK_SCRIPT(),
      [GAMES]: [{ timeout: true }, { status: 200, body: GAMES_HTML }],
    };
    const { payload, paths } = await scrape({ ...JOB, standings: false }, script);
    assert.deepEqual(paths, [GAMES, GAMES]);
    assert.equal(payload.fixtures.status, "OK");
  });

  test("a 5xx: one retry, then SOURCE_ERROR", async () => {
    const script = { ...OK_SCRIPT(), [LEAGUE]: [{ status: 502, body: "" }] };
    const { payload, paths } = await scrape({ ...JOB, fixtures: false }, script);
    assert.deepEqual(paths, [LEAGUE, LEAGUE]);
    assert.deepEqual(payload.standings, {
      status: "FAILED",
      reason: "SOURCE_ERROR",
      detail: "502 from source",
    });
  });

  test("a 404: no retry", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 404, body: "" }] };
    const { payload, paths } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES, LEAGUE, TABLE]);
    assert.equal(payload.fixtures.reason, "SOURCE_ERROR");
    assert.equal(payload.standings.status, "OK");
  });

  test("a network error: no retry, SOURCE_ERROR", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ network: true }] };
    const { payload, paths } = await scrape({ ...JOB, standings: false }, script);
    assert.deepEqual(paths, [GAMES]);
    assert.equal(payload.fixtures.reason, "SOURCE_ERROR");
  });

  test("only one retry per scrape", async () => {
    const script = {
      ...OK_SCRIPT(),
      [GAMES]: [{ status: 500, body: "" }],
      [LEAGUE]: [{ status: 500, body: "" }],
    };
    const { paths } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES, GAMES, LEAGUE]);
  });

  test("a fixtures parse failure still attempts the standings", async () => {
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 200, body: "<html></html>" }] };
    const { payload, paths } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES, LEAGUE, TABLE]);
    assert.deepEqual(payload.fixtures, {
      status: "FAILED",
      reason: "PARSE_ERROR",
      detail: "no game rows on the games page",
    });
    assert.equal(payload.standings.status, "OK");
  });

  test("season start (round 0): standings PARSE_ERROR, no LeagueTable request, fixtures OK", async () => {
    const script = {
      ...OK_SCRIPT(),
      [LEAGUE]: [{ status: 200, body: leaguePage({ round: "0" }) }],
    };
    const { payload, paths } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES, LEAGUE]);
    assert.equal(payload.fixtures.status, "OK");
    assert.deepEqual(payload.standings, {
      status: "FAILED",
      reason: "PARSE_ERROR",
      detail: "no standings round on the league page",
    });
  });

  test("the 90 s cap: unfinished parts are TIMEOUT", async () => {
    // The games page takes 87 s: no room for the 4 s pause and the league page.
    const script = { ...OK_SCRIPT(), [GAMES]: [{ status: 200, body: GAMES_HTML, ms: 87000 }] };
    const { payload, paths } = await scrape(JOB, script);
    assert.deepEqual(paths, [GAMES]);
    assert.equal(payload.fixtures.status, "OK");
    assert.deepEqual(payload.standings, {
      status: "FAILED",
      reason: "TIMEOUT",
      detail: "overall scrape time limit reached",
    });
  });

  test("the cap shortens a request's timeout and leaves no room for a retry", async () => {
    const script = {
      ...OK_SCRIPT(),
      [GAMES]: [{ status: 200, body: GAMES_HTML, ms: 70000 }],
      [LEAGUE]: [{ timeout: true }],
    };
    const { payload, source } = await scrape(JOB, script);
    assert.deepEqual(
      source.requests.map((r) => [r.path, r.timeoutMs]),
      [
        [GAMES, 30000],
        [LEAGUE, 16000],
      ],
    );
    assert.equal(payload.standings.reason, "TIMEOUT");
  });

  test("an aborted scrape rejects and closes the browser", async () => {
    const controller = new AbortController();
    const clock = fakeClock();
    const source = fakeSource(clock, OK_SCRIPT());
    const sleep = clock.sleep;
    clock.sleep = async (ms, signal) => {
      controller.abort();
      return sleep(ms, signal);
    };
    await assert.rejects(
      runScrape(JOB, { source, clock, log: createMemoryLogger(), signal: controller.signal }),
      { name: "AbortError" },
    );
    assert.equal(source.closed, 1);
  });

  test("standings rows come from the LeagueTable response, round from the league page", async () => {
    const { payload } = await scrape({ ...JOB, fixtures: false }, OK_SCRIPT());
    assert.equal(payload.standings.round, 6);
    assert.equal(payload.standings.rows.length, 18);
    assert.ok(TABLE_XML.length > 0);
  });
});
