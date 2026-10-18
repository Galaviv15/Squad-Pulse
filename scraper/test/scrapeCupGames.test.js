// The scrape flow with the cup step (KAN-85): team page → GetTeamNatCupData, its outcomes, and
// played cup games joining the game-details pick.

import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { createMemoryLogger } from "../src/log.js";
import { parseCupGames } from "../src/parseCupGames.js";
import { parseGamesPage } from "../src/parseFixtures.js";
import { runScrape } from "../src/scrape.js";
import { combineRows, selectGames } from "../src/selectGames.js";
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
const TEAM = "/team-details/";
const NATCUP = "/Components.asmx/GetTeamNatCupData";
const GAME = "/leagues/games/game/";
const gameKey = (id) => `${GAME}?game_id=${id}`;
const ORIGIN = "https://www.football.org.il/";
const SDEROT = "מועדון כדורגל שדרות";
const TEAM_HTML = fixture("team-7617-s28.html");
const NATCUP_XML = fixture("natcup-7617-s28.xml");
const NOT_ATTEMPTED_BLOCKED = {
  status: "FAILED",
  reason: "SOURCE_BLOCKED",
  detail: "not attempted: an earlier request was blocked",
};
// League games without a game fixture: known, so the pick is 1123491 (cup, 27/08) and 1122634
// (league, 09/09) at the fake clock's 2026-10-11. The cup's 1142007 (12/10) isn't 24 h old yet.
const NO_FIXTURE = [1122631, 1122650, 1122651];
const JOB_CUP = Object.freeze({
  runId: "run-c",
  teamId: 7617,
  leagueId: 62,
  seasonId: 28,
  fixtures: false,
  standings: false,
  cupGames: true,
});
const JOB_FIVE = Object.freeze({
  ...JOB_ALL,
  gameDetails: true,
  knownGameIds: NO_FIXTURE,
  cupGames: true,
});

const cupScript = (
  team = [{ status: 200, body: TEAM_HTML }],
  natcup = [{ status: 200, body: NATCUP_XML }],
) => ({
  [TEAM]: team,
  [NATCUP]: natcup,
});
const gamePages = () => ({
  [gameKey(1123491)]: [{ status: 200, body: fixture("game-1123491.html") }],
  [gameKey(1122634)]: [{ status: 200, body: fixture("game-1122634.html") }],
  [gameKey(1143937)]: [{ status: 200, body: fixture("game-1143937.html") }],
});
const fullScript = (cup = cupScript()) => ({ ...OK_SCRIPT(), ...cup, ...gamePages() });

async function scrape(job, steps, extra = {}) {
  const clock = fakeClock();
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

const stateCup = (entry) => [{ competition: "STATE_CUP", ...entry }];

describe("the cup step's sequence", () => {
  test("all five parts: 8 requests in order, exact URLs, kinds and timeouts", async () => {
    const { payload, source } = await scrape(JOB_FIVE, fullScript());
    assert.deepEqual(
      source.requests.map(({ kind, url, timeoutMs }) => [kind, url, timeoutMs]),
      [
        ["document", `${ORIGIN}team-details/team-games/?team_id=7617&season_id=28`, 30_000],
        [
          "fetch",
          `${ORIGIN}Components.asmx/GetTeamPlayersStatisticsList?teamId=7617&seasonId=28&isFemale=%22False%22`,
          20_000,
        ],
        ["document", `${ORIGIN}leagues/league/?league_id=62&season_id=28`, 30_000],
        ["fetch", source.requests[3].url, 20_000],
        ["document", `${ORIGIN}team-details/?team_id=7617&season_id=28`, 30_000],
        ["fetch", `${ORIGIN}Components.asmx/GetTeamNatCupData?team_id=7617&season_id=28`, 20_000],
        ["document", `${ORIGIN}leagues/games/game/?game_id=1123491`, 30_000],
        ["document", `${ORIGIN}leagues/games/game/?game_id=1122634`, 30_000],
      ],
    );
    for (const { url } of source.requests) assert.equal(url, new URL(url).href);
    for (const part of ["fixtures", "standings", "playerStats", "gameDetails", "cupGames"]) {
      assert.equal(payload[part].status, "OK", part);
    }
    assert.deepEqual(
      payload.cupGames.competitions,
      stateCup({ status: "OK", games: parseCupGames(NATCUP_XML, SDEROT).games }),
    );
    assert.deepEqual(
      payload.gameDetails.games.map((g) => [g.gameId, g.competition, g.round, g.ownSide]),
      [
        [1123491, "גביע המדינה ווינר", 2, "HOME"],
        [1122634, "ליגה א' דרום", 2, "HOME"],
      ],
    );
    assert.deepEqual(payload.gameDetails.failures, []);
  });

  test("cupGames alone: 2 requests, the other parts null", async () => {
    const { payload, paths, clock } = await scrape(JOB_CUP, cupScript());
    assert.deepEqual(paths, [TEAM, NATCUP]);
    assert.deepEqual(
      ["fixtures", "standings", "playerStats", "gameDetails"].map((p) => payload[p]),
      [null, null, null, null],
    );
    assert.equal(payload.cupGames.status, "OK");
    assert.equal(payload.cupGames.fetchedAt, new Date(clock.now()).toISOString());
    assert.equal(payload.cupGames.competitions[0].games.length, 2);
  });

  test("fetchedAt is the NatCup answer's time; the team page's if NatCup failed", async () => {
    const ok = await scrape(
      JOB_CUP,
      cupScript(undefined, [{ status: 200, body: NATCUP_XML, ms: 700 }]),
    );
    assert.equal(ok.payload.cupGames.fetchedAt, new Date(ok.clock.now()).toISOString());
    const failed = await scrape(
      JOB_CUP,
      cupScript([{ status: 200, body: TEAM_HTML, ms: 100 }], [{ status: 404, body: "", ms: 700 }]),
    );
    // Team page done at 100 ms; NatCup after a 4 s pause and 700 ms.
    assert.equal(
      failed.payload.cupGames.fetchedAt,
      new Date(Date.parse("2026-10-11T09:00:00Z") + 100).toISOString(),
    );
  });
});

describe("the cup part's outcomes", () => {
  test("browser failed to start → FAILED SOURCE_ERROR", async () => {
    const clock = fakeClock();
    const source = {
      origin: ORIGIN,
      open: async () => {
        throw new Error("no chromium");
      },
    };
    const payload = await runScrape(JOB_CUP, { source, clock, log: createMemoryLogger() });
    assert.deepEqual(payload.cupGames, {
      status: "FAILED",
      reason: "SOURCE_ERROR",
      detail: "browser failed to start",
    });
  });

  test("an earlier request blocked → FAILED SOURCE_BLOCKED not attempted, no cup request", async () => {
    const { payload, paths } = await scrape(JOB_FIVE, {
      ...fullScript(),
      [GAMES]: [{ status: 403, body: BLOCK_HTML }],
    });
    assert.deepEqual(paths, [GAMES]);
    assert.deepEqual(payload.cupGames, NOT_ATTEMPTED_BLOCKED);
  });

  test("less than 15 s of the cap left → FAILED TIMEOUT not attempted (the guard)", async () => {
    const { payload, paths } = await scrape(JOB_CUP, cupScript(), { capMs: 14_999 });
    assert.deepEqual(paths, []);
    assert.deepEqual(payload.cupGames, {
      status: "FAILED",
      reason: "TIMEOUT",
      detail: "not attempted: less than 15 s of the scrape time limit left",
    });
    const exactly = await scrape(JOB_CUP, cupScript(), { capMs: 15_000 });
    assert.deepEqual(exactly.paths, [TEAM, NATCUP]);
  });

  test("a slow site before it: the guard skips the cup step and the game pages", async () => {
    const { payload, paths } = await scrape(JOB_FIVE, {
      ...fullScript(),
      [TABLE]: [{ status: 200, body: fixture("league-62-s28-table-round6.xml"), ms: 70_000 }],
    });
    assert.deepEqual(paths, [GAMES, STATS, LEAGUE, TABLE]);
    assert.equal(payload.cupGames.reason, "TIMEOUT");
    assert.equal(payload.standings.status, "OK");
    assert.deepEqual([payload.gameDetails.games, payload.gameDetails.failures], [[], []]);
  });

  for (const [name, answer, expected] of [
    ["a 404", { status: 404, body: "" }, { reason: "SOURCE_ERROR", detail: "404 from source" }],
    ["a network error", { network: true }, { reason: "SOURCE_ERROR", detail: "network error" }],
    [
      "a timeout twice",
      { timeout: true },
      { reason: "TIMEOUT", detail: "no answer from source within 30 s" },
    ],
    [
      "a 503 twice",
      { status: 503, body: "" },
      { reason: "SOURCE_ERROR", detail: "503 from source" },
    ],
  ]) {
    test(`the team page fails (${name}) → the part FAILED with that reason, no NatCup`, async () => {
      const { payload, paths } = await scrape(JOB_CUP, cupScript([answer]));
      assert.ok(!paths.includes(NATCUP));
      assert.deepEqual(payload.cupGames, { status: "FAILED", ...expected });
    });
  }

  test("the team page blocked → the part SOURCE_BLOCKED, game details not attempted", async () => {
    const { payload, paths } = await scrape(
      JOB_FIVE,
      fullScript(cupScript([{ status: 403, body: BLOCK_HTML }])),
    );
    assert.deepEqual(paths, [GAMES, STATS, LEAGUE, TABLE, TEAM]);
    assert.deepEqual(payload.cupGames, {
      status: "FAILED",
      reason: "SOURCE_BLOCKED",
      detail: "403 from source (blocked)",
    });
    assert.deepEqual(payload.gameDetails, NOT_ATTEMPTED_BLOCKED);
    assert.equal(payload.fixtures.status, "OK");
  });

  test("the team page's h1 doesn't parse → the part FAILED PARSE_ERROR, no NatCup", async () => {
    const html = TEAM_HTML.replace("class='big'", "class='small'");
    const { payload, paths } = await scrape(JOB_CUP, cupScript([{ status: 200, body: html }]));
    assert.deepEqual(paths, [TEAM]);
    assert.deepEqual(payload.cupGames, {
      status: "FAILED",
      reason: "PARSE_ERROR",
      detail: "expected one h1.page_main_title span.big",
    });
  });

  test("NatCup fails → the part OK, STATE_CUP FAILED with that reason", async () => {
    const { payload } = await scrape(JOB_CUP, cupScript(undefined, [{ status: 404, body: "" }]));
    assert.equal(payload.cupGames.status, "OK");
    assert.deepEqual(
      payload.cupGames.competitions,
      stateCup({ status: "FAILED", reason: "SOURCE_ERROR", detail: "404 from source" }),
    );
  });

  test("NatCup blocked → STATE_CUP SOURCE_BLOCKED, then the scrape stops: game details not attempted", async () => {
    const { payload, paths } = await scrape(
      JOB_FIVE,
      fullScript(cupScript(undefined, [{ status: 403, body: BLOCK_HTML }])),
    );
    assert.deepEqual(paths, [GAMES, STATS, LEAGUE, TABLE, TEAM, NATCUP]);
    assert.deepEqual(
      payload.cupGames.competitions,
      stateCup({ status: "FAILED", reason: "SOURCE_BLOCKED", detail: "403 from source (blocked)" }),
    );
    assert.deepEqual(payload.gameDetails, NOT_ATTEMPTED_BLOCKED);
  });

  test("NatCup doesn't parse → STATE_CUP FAILED PARSE_ERROR; every other part as without cupGames", async () => {
    const otherName = TEAM_HTML.replace(SDEROT, "מ.כ. שדרות");
    const withCup = await scrape(
      JOB_FIVE,
      fullScript(cupScript([{ status: 200, body: otherName }])),
    );
    assert.deepEqual(
      withCup.payload.cupGames.competitions,
      stateCup({
        status: "FAILED",
        reason: "PARSE_ERROR",
        detail: "neither cup row teams are our team",
      }),
    );
    const without = await scrape({ ...JOB_FIVE, cupGames: false }, fullScript());
    for (const part of ["fixtures", "standings", "playerStats"]) {
      assert.deepEqual(withCup.payload[part], without.payload[part], part);
    }
    // A failed cup list adds no cup rows: the pick is the league rows alone, as without cupGames.
    assert.deepEqual(withCup.gameIds, without.gameIds);
    assert.deepEqual(withCup.gameIds, [1122634, 1143937]);
    assert.equal(without.payload.cupGames, null);
  });

  test("a list that breaks the contract → STATE_CUP FAILED, and its rows don't join the pick", async () => {
    const long = NATCUP_XML.replace("שמשון פזטל תל אביב", "א".repeat(101));
    assert.notEqual(long, NATCUP_XML);
    const { payload, gameIds } = await scrape(
      JOB_FIVE,
      fullScript(cupScript(undefined, [{ status: 200, body: long }])),
    );
    assert.deepEqual(
      payload.cupGames.competitions,
      stateCup({
        status: "FAILED",
        reason: "PARSE_ERROR",
        detail: "contract check: competition.games[0].awayTeamName: must be 1..100 characters",
      }),
    );
    assert.deepEqual(gameIds, [1122634, 1143937]);
  });

  test("a broken envelope → STATE_CUP FAILED PARSE_ERROR naming the endpoint", async () => {
    const { payload } = await scrape(
      JOB_CUP,
      cupScript(undefined, [{ status: 200, body: "<html>oops</html>" }]),
    );
    assert.deepEqual(
      payload.cupGames.competitions,
      stateCup({
        status: "FAILED",
        reason: "PARSE_ERROR",
        detail: "no string root element in the GetTeamNatCupData response",
      }),
    );
  });

  test("Games null → STATE_CUP OK with no games", async () => {
    const empty = fixture("totocup-7617-s28.xml");
    const { payload } = await scrape(JOB_CUP, cupScript(undefined, [{ status: 200, body: empty }]));
    assert.deepEqual(payload.cupGames.competitions, stateCup({ status: "OK", games: [] }));
  });

  test("one retry, shared: used by the team-games page, a 503 on the team page isn't retried", async () => {
    const { payload, paths, clock } = await scrape(JOB_FIVE, {
      ...fullScript(
        cupScript([
          { status: 503, body: "" },
          { status: 200, body: TEAM_HTML },
        ]),
      ),
      [GAMES]: [
        { status: 503, body: "" },
        { status: 200, body: GAMES_HTML },
      ],
    });
    assert.deepEqual(
      paths.filter((p) => p === GAMES || p === TEAM),
      [GAMES, GAMES, TEAM],
    );
    assert.deepEqual(payload.cupGames, {
      status: "FAILED",
      reason: "SOURCE_ERROR",
      detail: "503 from source",
    });
    assert.equal(clock.sleeps.filter((ms) => ms === 10_000).length, 1);
  });

  test("one retry, shared: used by NatCup, a later game page's 503 isn't retried", async () => {
    const { payload, gameIds } = await scrape(JOB_FIVE, {
      ...fullScript(
        cupScript(undefined, [
          { status: 503, body: "" },
          { status: 200, body: NATCUP_XML },
        ]),
      ),
      [gameKey(1123491)]: [{ status: 503, body: "" }],
    });
    assert.equal(payload.cupGames.competitions[0].status, "OK");
    assert.deepEqual(gameIds, [1123491, 1122634]);
    assert.deepEqual(payload.gameDetails.failures, [
      { gameId: 1123491, reason: "SOURCE_ERROR", detail: "503 from source" },
    ]);
  });

  test("logs ids, counts and statuses only: never a team name, a round or our name", async () => {
    const { log } = await scrape(JOB_FIVE, fullScript());
    const all = log.lines.join("\n");
    assert.match(all, / cup competition competition=STATE_CUP status=OK games=2/);
    for (const game of parseCupGames(NATCUP_XML, SDEROT).games) {
      for (const text of [game.homeTeamName, game.awayTeamName, game.round, game.venue]) {
        assert.ok(!all.includes(text), "a name, round or venue was logged");
      }
    }
  });
});

describe("cup rows in the game-details pick", () => {
  test("combineRows: league rows first, cup rows tagged CUP; a gameId in both lists kept once", () => {
    const league = parseGamesPage(GAMES_HTML);
    const cup = parseCupGames(NATCUP_XML, SDEROT).games;
    const { rows, duplicates } = combineRows(league, cup);
    assert.equal(rows.length, league.length + 2);
    assert.deepEqual(
      rows.slice(-2).map((r) => [r.gameId, r.kind]),
      [
        [1123491, "CUP"],
        [1142007, "CUP"],
      ],
    );
    assert.ok(rows.slice(0, league.length).every((r) => r.kind === "LEAGUE"));
    assert.deepEqual(duplicates, []);
    const clash = combineRows(league, [
      { ...cup[0], gameId: 1122634 },
      { ...cup[0], gameId: null },
    ]);
    assert.deepEqual(clash.duplicates, [1122634]);
    assert.equal(clash.rows.filter((r) => r.gameId === 1122634).length, 1);
    assert.equal(clash.rows.find((r) => r.gameId === 1122634).kind, "LEAGUE");
    assert.equal(clash.rows.filter((r) => r.gameId === null && r.kind === "CUP").length, 1);
  });

  test("oldest first across competitions, at most 2, the same 24 h rule and knownGameIds", () => {
    const { rows } = combineRows(
      parseGamesPage(GAMES_HTML),
      parseCupGames(NATCUP_XML, SDEROT).games,
    );
    const at = (iso) => Date.parse(iso);
    const pick = (known, now) => selectGames(rows, known, now).map((r) => [r.gameId, r.kind]);
    assert.deepEqual(pick(NO_FIXTURE, at("2026-10-11T09:00:00Z")), [
      [1123491, "CUP"],
      [1122634, "LEAGUE"],
    ]);
    assert.deepEqual(pick([...NO_FIXTURE, 1123491], at("2026-10-11T09:00:00Z")), [
      [1122634, "LEAGUE"],
      [1143937, "LEAGUE"],
    ]);
    // 1142007: 12/10 15:00 Israel = 12:00Z; 24 h later it qualifies (the league's last is 09/10).
    const later = selectGames(
      rows,
      [1123491, 1122631, 1122634, 1122650, 1122651, 1143937, 1122670],
      at("2026-10-13T12:00:00Z"),
    );
    assert.deepEqual(
      later.map((r) => r.gameId),
      [1142007],
    );
    assert.deepEqual(
      selectGames(
        rows,
        [1123491, 1122631, 1122634, 1122650, 1122651, 1143937, 1122670],
        at("2026-10-13T11:59:59.999Z"),
      ),
      [],
    );
  });

  test("a gameId in both lists is fetched once (as its league row) and logged at WARN with the id", async () => {
    const clash = NATCUP_XML.replace("game_id=1123491", "game_id=1122634");
    const { payload, gameIds, log } = await scrape(
      JOB_FIVE,
      fullScript(cupScript(undefined, [{ status: 200, body: clash }])),
    );
    assert.deepEqual(gameIds, [1122634, 1143937]);
    assert.deepEqual(
      payload.gameDetails.games.map((g) => g.gameId),
      [1122634, 1143937],
    );
    assert.ok(
      log.lines.some((l) =>
        / WARN run=\S+ game in both the league and the cup list gameId=1122634$/.test(l),
      ),
    );
  });

  test("the team-games page didn't parse: game details FAILED as today, never picked from cup rows alone", async () => {
    const { payload, gameIds } = await scrape(JOB_FIVE, {
      ...fullScript(),
      [GAMES]: [{ status: 200, body: "<html><body>no games</body></html>" }],
    });
    assert.equal(payload.cupGames.competitions[0].status, "OK");
    assert.deepEqual(gameIds, []);
    assert.deepEqual(payload.gameDetails, {
      status: "FAILED",
      reason: "PARSE_ERROR",
      detail: "not attempted: the team games page didn't parse (no game rows on the games page)",
    });
  });

  test("a cup game page with the other side or score → that game's PARSE_ERROR, by ownSide / score", async () => {
    const swapped = NATCUP_XML.replace("\\u003e0-1\\u003c", "\\u003e1-1\\u003c");
    assert.notEqual(swapped, NATCUP_XML);
    const { payload } = await scrape(
      JOB_FIVE,
      fullScript(cupScript(undefined, [{ status: 200, body: swapped }])),
    );
    assert.deepEqual(payload.gameDetails.failures, [
      { gameId: 1123491, reason: "PARSE_ERROR", detail: "awayScore differs from the cup row" },
    ]);
  });
});
