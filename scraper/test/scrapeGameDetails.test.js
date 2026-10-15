// The scrape flow with game details (KAN-80): selection, the fourth step, failures per game.

import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createMemoryLogger } from "../src/log.js";
import { runScrape } from "../src/scrape.js";
import { israelInstant, selectGames } from "../src/selectGames.js";
import {
  BLOCK_HTML,
  GAMES_HTML,
  JOB_ALL,
  OK_SCRIPT,
  fakeClock,
  fakeSource,
  fixture,
} from "./helpers.js";

const GAMES = "/team-details/team-games/";
const STATS = "/Components.asmx/GetTeamPlayersStatisticsList";
const LEAGUE = "/leagues/league/";
const TABLE = "/Components.asmx/LeagueTable";
const GAME = "/leagues/games/game/";
const gameKey = (id) => `${GAME}?game_id=${id}`;
const NOT_ATTEMPTED_BLOCKED = {
  status: "FAILED",
  reason: "SOURCE_BLOCKED",
  detail: "not attempted: an earlier request was blocked",
};
// The team-games fixture's played games, oldest first: 1122631, 1122634, 1122650, 1122651,
// 1143937 (01/10), 1122670 (09/10). Only 1122634, 1143937 and 1122670 have a game fixture.
const NO_FIXTURE = [1122631, 1122650, 1122651];
const HTML = {
  1122634: fixture("game-1122634.html"),
  1143937: fixture("game-1143937.html"),
  1122670: fixture("game-1122670.html"),
};
const JOB_GAMES = Object.freeze({ ...JOB_ALL, gameDetails: true, knownGameIds: NO_FIXTURE });
const ok = (id, extra = {}) => [{ status: 200, body: HTML[id], ...extra }];

/** OK_SCRIPT plus the game pages, each answered by its game_id. */
const script = (games = { 1122634: ok(1122634), 1143937: ok(1143937), 1122670: ok(1122670) }) => ({
  ...OK_SCRIPT(),
  ...Object.fromEntries(Object.entries(games).map(([id, answers]) => [gameKey(id), answers])),
});

async function scrape(job, steps, extra = {}, start = undefined) {
  const clock = fakeClock(start);
  const source = fakeSource(clock, steps);
  const log = createMemoryLogger();
  const payload = await runScrape(job, { source, clock, log, random: () => 0.5, ...extra });
  return {
    payload,
    source,
    clock,
    log,
    paths: source.requests.map((r) => r.path),
    gameIds: source.requests
      .filter((r) => r.path === GAME)
      .map((r) => Number(new URL(r.url).searchParams.get("game_id"))),
  };
}

const row = (gameId, date, kickoffTime, scored = true) => ({
  gameId,
  date,
  kickoffTime,
  homeTeamName: "א",
  awayTeamName: "ב",
  venue: null,
  homeScore: scored ? 1 : null,
  awayScore: scored ? 0 : null,
});
const at = (iso) => Date.parse(iso);

describe("selection (selectGames)", () => {
  test("Israel time: Intl has the zone's data, and DST ends on 2026-10-25", () => {
    assert.equal(israelInstant("2026-10-24", "20:00"), at("2026-10-24T17:00:00Z")); // +03
    assert.equal(israelInstant("2026-10-25", "20:00"), at("2026-10-25T18:00:00Z")); // +02
    assert.equal(israelInstant("2026-03-27", "12:00"), at("2026-03-27T09:00:00Z")); // +03 again
    assert.equal(israelInstant("2026-01-15", "00:30"), at("2026-01-14T22:30:00Z"));
  });

  test("24 h after kickoff: before DST ends (+03), on both sides of the boundary", () => {
    const rows = [row(1, "2026-10-24", "20:00")];
    assert.deepEqual(selectGames(rows, [], at("2026-10-25T16:59:59.999Z")), []);
    assert.deepEqual(selectGames(rows, [], at("2026-10-25T17:00:00Z")), rows);
  });

  test("24 h after kickoff: after DST ends (+02), where a fixed +03 would be an hour early", () => {
    const rows = [row(1, "2026-10-25", "20:00")];
    assert.deepEqual(selectGames(rows, [], at("2026-10-26T17:30:00Z")), []);
    assert.deepEqual(selectGames(rows, [], at("2026-10-26T17:59:59.999Z")), []);
    assert.deepEqual(selectGames(rows, [], at("2026-10-26T18:00:00Z")), rows);
  });

  test("a null kickoff counts as 23:59 of its date", () => {
    const rows = [row(1, "2026-10-09", null)];
    assert.deepEqual(selectGames(rows, [], at("2026-10-10T20:58:59.999Z")), []);
    assert.deepEqual(selectGames(rows, [], at("2026-10-10T20:59:00Z")), rows);
  });

  test("order: date, then kickoff (null as 23:59), then gameId; ids aren't chronological", () => {
    const now = at("2026-12-01T00:00:00Z");
    const rows = [
      row(5, "2026-10-02", "18:00"),
      row(9, "2026-10-01", null),
      row(1, "2026-10-01", "23:59"),
      row(2, "2026-10-01", "20:00"),
    ];
    assert.deepEqual(
      selectGames(rows, [], now).map((r) => r.gameId),
      [2, 1],
    );
    assert.deepEqual(
      selectGames(rows, [2], now).map((r) => r.gameId),
      [1, 9],
    );
  });

  test("only games with a gameId and both scores; knownGameIds skipped; at most 2", () => {
    const now = at("2026-12-01T00:00:00Z");
    const rows = [
      row(null, "2026-09-01", "20:00"),
      row(3, "2026-09-02", "20:00", false),
      row(4, "2026-09-03", "20:00"),
      row(5, "2026-09-04", "20:00"),
      row(6, "2026-09-05", "20:00"),
      row(7, "2026-09-06", "20:00"),
    ];
    assert.deepEqual(
      selectGames(rows, [], now).map((r) => r.gameId),
      [4, 5],
    );
    assert.deepEqual(
      selectGames(rows, [4, 6], now).map((r) => r.gameId),
      [5, 7],
    );
    assert.deepEqual(selectGames(rows, [4, 5, 6, 7], now), []);
  });
});

describe("scrape with game details", () => {
  test("all four parts: 6 requests, the game pages last, oldest unknown games first", async () => {
    const { payload, source, clock, gameIds } = await scrape(JOB_GAMES, script());
    assert.deepEqual(
      source.requests.map((r) => r.path),
      [GAMES, STATS, LEAGUE, TABLE, GAME, GAME],
    );
    assert.deepEqual(gameIds, [1122634, 1143937]);
    assert.equal(
      source.requests[4].url,
      "https://www.football.org.il/leagues/games/game/?game_id=1122634",
    );
    assert.deepEqual(
      source.requests.slice(4).map((r) => [r.kind, r.timeoutMs]),
      [
        ["document", 30000],
        ["document", 30000],
      ],
    );
    assert.deepEqual(clock.sleeps, [4000, 4000, 4000, 4000, 4000]);
    const part = payload.gameDetails;
    assert.equal(part.status, "OK");
    assert.deepEqual(
      part.games.map((g) => [g.gameId, g.ownSide, g.date, g.kickoffTime]),
      [
        [1122634, "HOME", "2026-09-09", "19:00"],
        [1143937, "HOME", "2026-10-01", "19:00"],
      ],
    );
    assert.deepEqual(part.failures, []);
    // Taken once, after the last game page: 6 × 100 ms + 5 × 4 s.
    assert.equal(part.fetchedAt, new Date(at("2026-10-11T09:00:00Z") + 20_600).toISOString());
    for (const name of ["fixtures", "standings", "playerStats"])
      assert.equal(payload[name].status, "OK");
  });

  test("by date, not id: 1143937 (round 5, 01/10) before 1122670 (round 6, 09/10)", async () => {
    const job = { ...JOB_GAMES, knownGameIds: [...NO_FIXTURE, 1122634] };
    const { payload, gameIds } = await scrape(job, script());
    assert.ok(1143937 > 1122670);
    assert.deepEqual(gameIds, [1143937, 1122670]);
    assert.deepEqual(
      payload.gameDetails.games.map((g) => g.gameId),
      [1143937, 1122670],
    );
  });

  test("a game less than 24 h after kickoff is skipped, and taken once 24 h have passed", async () => {
    // 1122670 kicked off on 2026-10-09 at 13:35 Israel time (+03) = 10:35Z. The selection runs
    // after LeagueTable: 4 × 100 ms + 3 × 4 s = 12.4 s into the scrape.
    const job = { ...JOB_GAMES, knownGameIds: [...NO_FIXTURE, 1122634, 1143937] };
    const threshold = at("2026-10-10T10:35:00Z") - 12_400;
    const early = await scrape(job, script(), {}, threshold - 1);
    assert.deepEqual(early.gameIds, []);
    assert.deepEqual(early.payload.gameDetails.games, []);
    const late = await scrape(job, script(), {}, threshold);
    assert.deepEqual(late.gameIds, [1122670]);
  });

  test("the other parts are identical with and without gameDetails", async () => {
    const without = await scrape(JOB_ALL, OK_SCRIPT());
    const withGames = await scrape(JOB_GAMES, script());
    for (const name of ["fixtures", "standings", "playerStats"]) {
      assert.deepEqual(withGames.payload[name], without.payload[name], name);
    }
    assert.equal(without.payload.gameDetails, null);
  });

  test("only gameDetails: the team games page, then the game pages", async () => {
    const job = { ...JOB_GAMES, fixtures: false, standings: false, playerStats: false };
    const { payload, paths } = await scrape(job, script());
    assert.deepEqual(paths, [GAMES, GAME, GAME]);
    assert.equal(payload.fixtures, null);
    assert.equal(payload.gameDetails.games.length, 2);
  });

  test("nothing qualifies → OK with empty lists, no game request", async () => {
    const job = { ...JOB_GAMES, knownGameIds: [...NO_FIXTURE, 1122634, 1143937, 1122670] };
    const { payload, paths } = await scrape(job, script());
    assert.ok(!paths.includes(GAME));
    assert.deepEqual(payload.gameDetails, {
      status: "OK",
      fetchedAt: payload.gameDetails.fetchedAt,
      games: [],
      failures: [],
    });
  });

  test("one game parses, one fails PARSE_ERROR → OK with one game and one failure", async () => {
    const steps = script({ 1122634: ok(1122634), 1143937: ok(1122670) });
    const { payload } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(
      payload.gameDetails.games.map((g) => g.gameId),
      [1122634],
    );
    assert.deepEqual(payload.gameDetails.failures, [
      {
        gameId: 1143937,
        reason: "PARSE_ERROR",
        detail: "no game section with the requested game id",
      },
    ]);
  });

  test("a game answering 500 twice → SOURCE_ERROR; the other game is still fetched", async () => {
    const steps = script({ 1122634: [{ status: 500, body: "" }], 1143937: ok(1143937) });
    const { payload, gameIds, clock } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(gameIds, [1122634, 1122634, 1143937]);
    assert.deepEqual(clock.sleeps, [4000, 4000, 4000, 4000, 10000, 4000]);
    assert.deepEqual(payload.gameDetails.failures, [
      { gameId: 1122634, reason: "SOURCE_ERROR", detail: "500 from source" },
    ]);
    assert.deepEqual(
      payload.gameDetails.games.map((g) => g.gameId),
      [1143937],
    );
  });

  test("the retry is shared: used by game 1, so game 2's timeout isn't retried", async () => {
    const steps = script({ 1122634: [{ status: 500, body: "" }], 1143937: [{ timeout: true }] });
    const { payload, gameIds } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(gameIds, [1122634, 1122634, 1143937]);
    assert.deepEqual(payload.gameDetails.failures, [
      { gameId: 1122634, reason: "SOURCE_ERROR", detail: "500 from source" },
      { gameId: 1143937, reason: "TIMEOUT", detail: "no answer from source within 30 s" },
    ]);
    assert.deepEqual(payload.gameDetails.games, []);
  });

  test("a 404 → SOURCE_ERROR, not retried; the next game goes on", async () => {
    const steps = script({ 1122634: [{ status: 404, body: "" }], 1143937: ok(1143937) });
    const { payload, gameIds } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(gameIds, [1122634, 1143937]);
    assert.deepEqual(payload.gameDetails.failures, [
      { gameId: 1122634, reason: "SOURCE_ERROR", detail: "404 from source" },
    ]);
  });

  test("a block on game 1 → its failure SOURCE_BLOCKED, game 2 absent and never requested", async () => {
    const steps = script({ 1122634: [{ status: 403, body: BLOCK_HTML }], 1143937: ok(1143937) });
    const { payload, gameIds, log } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(gameIds, [1122634]);
    assert.deepEqual(payload.gameDetails, {
      status: "OK",
      fetchedAt: payload.gameDetails.fetchedAt,
      games: [],
      failures: [
        { gameId: 1122634, reason: "SOURCE_BLOCKED", detail: "403 from source (blocked)" },
      ],
    });
    assert.ok(
      log.lines.some((l) => / game details not attempted: blocked gameId=1143937$/.test(l)),
    );
    for (const name of ["fixtures", "standings", "playerStats"])
      assert.equal(payload[name].status, "OK");
  });

  for (const [where, path] of [
    ["the team games page", GAMES],
    ["the stats", STATS],
    ["the league page", LEAGUE],
    ["LeagueTable", TABLE],
  ]) {
    test(`a block on ${where} → gameDetails FAILED SOURCE_BLOCKED, no game request`, async () => {
      const steps = { ...script(), [path]: [{ status: 403, body: BLOCK_HTML }] };
      const { payload, paths } = await scrape(JOB_GAMES, steps);
      assert.ok(!paths.includes(GAME));
      assert.deepEqual(payload.gameDetails, NOT_ATTEMPTED_BLOCKED);
    });
  }

  test("the team games page didn't load → gameDetails FAILED 'not attempted', like the stats", async () => {
    const steps = { ...script(), [GAMES]: [{ status: 404, body: "" }] };
    const { payload, paths } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(paths, [GAMES, LEAGUE, TABLE]);
    const notAttempted = {
      status: "FAILED",
      reason: "SOURCE_ERROR",
      detail: "not attempted: the team games page didn't load (404 from source)",
    };
    assert.deepEqual(payload.gameDetails, notAttempted);
    assert.deepEqual(payload.playerStats, notAttempted);
    assert.equal(payload.standings.status, "OK");
  });

  test("the team games page didn't parse → gameDetails FAILED PARSE_ERROR; the stats still run", async () => {
    const broken = GAMES_HTML.replace(
      '<span class="sr-only">תאריך</span>',
      '<span class="sr-only">יום</span>',
    );
    assert.notEqual(broken, GAMES_HTML);
    const steps = { ...script(), [GAMES]: [{ status: 200, body: broken }] };
    const { payload, paths } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(paths, [GAMES, STATS, LEAGUE, TABLE]);
    assert.equal(payload.fixtures.reason, "PARSE_ERROR");
    assert.equal(payload.playerStats.status, "OK");
    assert.deepEqual(payload.gameDetails, {
      status: "FAILED",
      reason: "PARSE_ERROR",
      detail: `not attempted: the team games page didn't parse (${payload.fixtures.detail})`,
    });
  });

  test("15 s guard: exactly 15 s left → game 2 is requested", async () => {
    // 4 × 100 ms… : games, pause, stats, pause, league, pause, table (29 s), pause, game 1 (29.7 s).
    const steps = script({ 1122634: ok(1122634, { ms: 29_700 }), 1143937: ok(1143937) });
    steps[TABLE] = [{ ...steps[TABLE][0], ms: 29_000 }];
    const { gameIds } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(gameIds, [1122634, 1143937]);
  });

  test("15 s guard: less than 15 s left → game 2 is absent and never requested", async () => {
    const steps = script({ 1122634: ok(1122634, { ms: 29_800 }), 1143937: ok(1143937) });
    steps[TABLE] = [{ ...steps[TABLE][0], ms: 29_000 }];
    const { payload, gameIds, log } = await scrape(JOB_GAMES, steps);
    assert.deepEqual(gameIds, [1122634]);
    assert.deepEqual(
      payload.gameDetails.games.map((g) => g.gameId),
      [1122634],
    );
    assert.deepEqual(payload.gameDetails.failures, []);
    assert.ok(
      log.lines.some((l) =>
        / INFO .* game details not attempted: time limit gameId=1143937$/.test(l),
      ),
    );
  });

  test("the guard is configurable (SCRAPE_DEFAULTS.gameMinRemainingMs)", async () => {
    const { gameIds } = await scrape(JOB_GAMES, script(), { gameMinRemainingMs: 90_000 });
    assert.deepEqual(gameIds, []);
  });

  test("a game cut by the cap mid-request → TIMEOUT failure", async () => {
    const steps = script({ 1122634: ok(1122634, { ms: 29_000 }), 1143937: [{ timeout: true }] });
    steps[TABLE] = [{ ...steps[TABLE][0], ms: 29_000 }];
    const { payload, source } = await scrape(JOB_GAMES, steps);
    assert.ok(source.requests.at(-1).timeoutMs < 30_000);
    assert.deepEqual(payload.gameDetails.failures, [
      { gameId: 1143937, reason: "TIMEOUT", detail: "overall scrape time limit reached" },
    ]);
  });

  test("the browser fails to start → gameDetails FAILED SOURCE_ERROR too", async () => {
    const source = {
      origin: "https://www.football.org.il/",
      open: async () => {
        throw new Error("no browser");
      },
    };
    const payload = await runScrape(JOB_GAMES, {
      source,
      clock: fakeClock(),
      log: createMemoryLogger(),
    });
    assert.deepEqual(payload.gameDetails, {
      status: "FAILED",
      reason: "SOURCE_ERROR",
      detail: "browser failed to start",
    });
  });

  test("a game breaking a contract rule (41 players) moves to failures; the other is kept", async () => {
    const extra = Array.from(
      { length: 21 },
      (_, i) =>
        `<a href="/players/player/?player_id=${950001 + i}"><div class="player"><span class="number">מס' 1</span><span class="name"><b>שם בדוי</b></span><span class="moves"></span></div></a>`,
    ).join("");
    const marker = '<h2 class="sr-only" id="GAME_PLAYER_TYPE_Bench_HOME">ביתית לא שותפו</h2>';
    assert.ok(HTML[1143937].includes(marker));
    const crowded = HTML[1143937].replace(marker, marker + extra);
    const steps = script({ 1122634: ok(1122634), 1143937: [{ status: 200, body: crowded }] });
    const { payload } = await scrape(JOB_GAMES, steps);
    assert.equal(payload.gameDetails.status, "OK");
    assert.deepEqual(
      payload.gameDetails.games.map((g) => g.gameId),
      [1122634],
    );
    assert.deepEqual(payload.gameDetails.failures, [
      {
        gameId: 1143937,
        reason: "PARSE_ERROR",
        detail: "contract check: game.players: must have 1..40 players",
      },
    ]);
  });

  test("warnings are logged at WARN with the gameId, and the game is still sent", async () => {
    const at = HTML[1122634].indexOf(
      "player_id=",
      HTML[1122634].indexOf("GAME_PLAYER_TYPE_ACTIVE_HOME"),
    );
    const start = HTML[1122634].lastIndexOf("<a ", at);
    const end = HTML[1122634].indexOf("</a>", at) + 4;
    const tenStarters = HTML[1122634].slice(0, start) + HTML[1122634].slice(end);
    const steps = script({ 1122634: [{ status: 200, body: tenStarters }], 1143937: ok(1143937) });
    const { payload, log } = await scrape(JOB_GAMES, steps);
    assert.equal(payload.gameDetails.games.length, 2);
    assert.ok(
      log.lines.some((l) =>
        / WARN run=run-1 game details check gameId=1122634 check=starters expected=11 actual=10$/.test(
          l,
        ),
      ),
      log.lines.join("\n"),
    );
  });

  test("no log line carries a name: ours, the opponent's or a team's", async () => {
    const runs = [
      await scrape(JOB_GAMES, script()),
      await scrape({ ...JOB_GAMES, knownGameIds: [...NO_FIXTURE, 1122634] }, script()),
      await scrape(JOB_GAMES, script({ 1122634: ok(1122670), 1143937: ok(1143937) })),
    ];
    const names = new Set();
    for (const { payload } of runs) {
      for (const game of payload.gameDetails.games) {
        for (const player of game.players) if (player.name !== null) names.add(player.name);
        names.add(game.homeTeamName);
        names.add(game.awayTeamName);
      }
    }
    assert.ok(names.size > 20, `${names.size}`);
    for (const { log } of runs) {
      assert.ok(log.lines.length > 5);
      for (const line of log.lines) {
        assert.ok(!line.includes("שחקן יריב"), line);
        for (const name of names) assert.ok(!line.includes(name), line);
      }
    }
  });
});
