import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  PARTS,
  enforceContract,
  failedPart,
  gameFailure,
  partViolation,
  payloadViolation,
} from "../src/contract.js";
import { runScrape } from "../src/scrape.js";
import { createMemoryLogger } from "../src/log.js";
import { JOB, JOB_ALL, OK_SCRIPT, contractExample, fakeClock, fakeSource } from "./helpers.js";

// The parts a JOB_ALL scrape fills (no gameDetails: it needs game pages, scrapeGameDetails.test.js).
const THREE_PARTS = ["fixtures", "standings", "playerStats"];

async function fixturePayload(job = JOB) {
  const clock = fakeClock();
  return runScrape(job, {
    source: fakeSource(clock, OK_SCRIPT()),
    clock,
    log: createMemoryLogger(),
    random: () => 0.5,
  });
}

/** A made-up player statistics part (never the fixture's names or ids). */
function madeUpStats() {
  const player = (playerId, name, starts, cameOnAsSub, minutes) => ({
    playerId,
    name,
    games: starts + cameOnAsSub,
    goals: 1,
    yellowCardsLeagueAndCup: 2,
    yellowCardsToto: 0,
    redCards: 0,
    starts,
    cameOnAsSub,
    subbedOff: 1,
    minutes,
  });
  return {
    status: "OK",
    fetchedAt: "2026-10-11T09:00:04.200Z",
    players: [
      player(900001, "כהן דניאל", 8, 2, 712),
      player(900002, "לוי אורי בן", 1, 0, 96),
      player(900003, "מזרחי אבירם", 0, 0, 0),
    ],
  };
}

async function threeParts() {
  return { ...(await fixturePayload()), playerStats: madeUpStats() };
}

const ALLOWED = {
  top: [
    "runId",
    "teamId",
    "leagueId",
    "seasonId",
    "fixtures",
    "standings",
    "playerStats",
    "gameDetails",
    "cupGames",
  ],
  fixtures: ["status", "fetchedAt", "games"],
  game: [
    "gameId",
    "date",
    "kickoffTime",
    "homeTeamName",
    "awayTeamName",
    "venue",
    "homeScore",
    "awayScore",
  ],
  standings: ["status", "fetchedAt", "round", "rows"],
  row: [
    "position",
    "teamId",
    "teamName",
    "played",
    "won",
    "drawn",
    "lost",
    "goalsFor",
    "goalsAgainst",
    "points",
  ],
};

function walkStrings(value, visit) {
  if (typeof value === "string") visit(value);
  else if (value && typeof value === "object")
    Object.values(value).forEach((v) => walkStrings(v, visit));
}

describe("local contract check", () => {
  test("accepts the request example in docs/scraper-backend-contract.md", () => {
    assert.equal(payloadViolation(contractExample()), null);
  });

  test("accepts the payload built from the fixtures, with both parts OK", async () => {
    const payload = await fixturePayload();
    assert.equal(payloadViolation(payload), null);
    assert.equal(payload.fixtures.status, "OK");
    assert.equal(payload.standings.status, "OK");
    assert.equal(payload.fixtures.games.length, 34);
    assert.equal(payload.standings.rows.length, 18);
    assert.equal(payload.standings.round, 6);
  });

  test("accepts the payload built from the fixtures, with all three parts OK", async () => {
    const payload = await fixturePayload(JOB_ALL);
    assert.equal(payloadViolation(payload), null);
    for (const part of THREE_PARTS) assert.equal(payload[part].status, "OK", part);
    assert.equal(payload.playerStats.players.length, 34);
    assert.deepEqual(enforceContract(payload), payload);
  });

  test('no "" anywhere and no property outside the contract', async () => {
    const payload = await fixturePayload(JOB_ALL);
    walkStrings(payload, (s) => assert.notEqual(s.trim(), ""));
    assert.deepEqual(Object.keys(payload).sort(), [...ALLOWED.top].sort());
    assert.deepEqual(Object.keys(payload.fixtures).sort(), [...ALLOWED.fixtures].sort());
    assert.deepEqual(Object.keys(payload.standings).sort(), [...ALLOWED.standings].sort());
    for (const g of payload.fixtures.games)
      assert.deepEqual(Object.keys(g).sort(), [...ALLOWED.game].sort());
    for (const r of payload.standings.rows)
      assert.deepEqual(Object.keys(r).sort(), [...ALLOWED.row].sort());
    assert.deepEqual(Object.keys(payload.playerStats).sort(), ["fetchedAt", "players", "status"]);
  });

  const brokenFixtures = {
    "a score out of range": (p) => (p.fixtures.games[0].homeScore = 100),
    "one score without the other": (p) => (p.fixtures.games[0].awayScore = null),
    "home = away": (p) => (p.fixtures.games[0].awayTeamName = p.fixtures.games[0].homeTeamName),
    'an empty venue ""': (p) => (p.fixtures.games[0].venue = ""),
    "a name over 100 chars": (p) => (p.fixtures.games[0].homeTeamName = "א".repeat(101)),
    "a venue over 200 chars": (p) => (p.fixtures.games[0].venue = "א".repeat(201)),
    "an unreal date": (p) => (p.fixtures.games[0].date = "2026-02-31"),
    "kickoff not HH:mm": (p) => (p.fixtures.games[0].kickoffTime = "9:00"),
    "gameId 0": (p) => (p.fixtures.games[0].gameId = 0),
    "no games": (p) => (p.fixtures.games = []),
    "101 games": (p) => (p.fixtures.games = Array(101).fill(p.fixtures.games[0])),
    "an extra property": (p) => (p.fixtures.games[0].round = 1),
    "a control character": (p) => (p.fixtures.games[0].homeTeamName = "a\tb"),
  };
  const brokenStats = {
    "a duplicate playerId": (p) =>
      (p.playerStats.players[1].playerId = p.playerStats.players[0].playerId),
    "81 players": (p) =>
      (p.playerStats.players = Array.from({ length: 81 }, (_, i) => ({
        ...p.playerStats.players[0],
        playerId: 1000 + i,
      }))),
    "0 players": (p) => (p.playerStats.players = []),
    "a null player": (p) => (p.playerStats.players[0] = null),
    "a count of 201": (p) => (p.playerStats.players[0].goals = 201),
    "a negative count": (p) => (p.playerStats.players[0].redCards = -1),
    "minutes 20001": (p) => (p.playerStats.players[0].minutes = 20001),
    "starts + cameOnAsSub ≠ games": (p) => (p.playerStats.players[0].games += 1),
    "a missing field": (p) => delete p.playerStats.players[0].subbedOff,
    "an unknown player property": (p) => (p.playerStats.players[0].shirtNumber = 9),
    "a name of 101 characters": (p) => (p.playerStats.players[0].name = "א".repeat(101)),
    "a control character in a name": (p) => (p.playerStats.players[0].name = "כהן\tדניאל"),
    'an empty name ""': (p) => (p.playerStats.players[0].name = "  "),
    "playerId 0": (p) => (p.playerStats.players[0].playerId = 0),
    "playerId over Java's int": (p) => (p.playerStats.players[0].playerId = 2147483648),
    "a non-integer count": (p) => (p.playerStats.players[0].goals = 1.5),
    "fetchedAt without an offset": (p) => (p.playerStats.fetchedAt = "2026-10-11T09:00:00"),
    "an unknown part property": (p) => (p.playerStats.round = 1),
  };
  const brokenStandings = {
    "won + drawn + lost ≠ played": (p) => (p.standings.rows[0].played += 1),
    "a duplicate teamId": (p) => (p.standings.rows[1].teamId = p.standings.rows[0].teamId),
    "a duplicate teamName": (p) => (p.standings.rows[1].teamName = p.standings.rows[0].teamName),
    "position beyond the row count": (p) => (p.standings.rows[0].position = 19),
    "position 0": (p) => (p.standings.rows[0].position = 0),
    "one row": (p) => (p.standings.rows = p.standings.rows.slice(0, 1)),
    "41 rows": (p) => (p.standings.rows = Array(41).fill(p.standings.rows[0])),
    "goals over 999": (p) => (p.standings.rows[0].goalsFor = 1000),
    "points under -99": (p) => (p.standings.rows[0].points = -100),
    'an empty team name ""': (p) => (p.standings.rows[0].teamName = ""),
    "round 0": (p) => (p.standings.round = 0),
    "fetchedAt without an offset": (p) => (p.standings.fetchedAt = "2026-10-11T09:00:00"),
  };

  for (const [part, cases] of [
    ["fixtures", brokenFixtures],
    ["standings", brokenStandings],
    ["playerStats", brokenStats],
  ]) {
    for (const [name, breakIt] of Object.entries(cases)) {
      test(`${part}: ${name} → FAILED PARSE_ERROR, the other parts stay OK`, async () => {
        const payload = structuredClone(await threeParts());
        breakIt(payload);
        assert.notEqual(payloadViolation(payload), null);
        const enforced = enforceContract(payload);
        assert.equal(enforced[part].status, "FAILED");
        assert.equal(enforced[part].reason, "PARSE_ERROR");
        assert.match(enforced[part].detail, new RegExp(`^contract check: ${part}`));
        for (const other of THREE_PARTS.filter((name) => name !== part)) {
          assert.equal(enforced[other].status, "OK", other);
        }
        assert.equal(payloadViolation(enforced), null);
      });
    }
  }

  test("playerStats: the detail names a row by index, never by name or playerId", async () => {
    const payload = structuredClone(await threeParts());
    payload.playerStats.players[2].games = 5;
    const { detail } = enforceContract(payload).playerStats;
    assert.equal(
      detail,
      "contract check: playerStats.players[2]: starts + cameOnAsSub must equal games",
    );
  });

  test("playerStats: minutes above 90 × games stay OK", async () => {
    const payload = await threeParts();
    assert.equal(payload.playerStats.players[1].minutes, 96);
    assert.equal(payload.playerStats.players[1].games, 1);
    assert.equal(payloadViolation(payload), null);
    assert.equal(enforceContract(payload).playerStats.status, "OK");
  });

  test("playerStats: exactly 80 players, counts of 200 and minutes of 20000 are OK", async () => {
    const payload = structuredClone(await threeParts());
    payload.playerStats.players = Array.from({ length: 80 }, (_, i) => ({
      ...payload.playerStats.players[0],
      playerId: 1 + i,
      goals: 200,
      minutes: 20000,
    }));
    payload.playerStats.players[0].playerId = 2147483647;
    assert.equal(payloadViolation(payload), null);
  });

  test("a FAILED playerStats part follows the contract", () => {
    const payload = {
      runId: null,
      teamId: 1,
      leagueId: 2,
      seasonId: 3,
      playerStats: failedPart("TIMEOUT", "no answer from source within 20 s"),
    };
    assert.equal(payloadViolation(payload), null);
  });

  test("needs at least one of the three parts", () => {
    const ids = { runId: null, teamId: 1, leagueId: 2, seasonId: 3 };
    assert.equal(
      payloadViolation({ ...ids, fixtures: null, standings: null, playerStats: null }),
      "request: needs at least one part",
    );
    assert.equal(payloadViolation(ids), "request: needs at least one part");
    assert.equal(payloadViolation({ ...ids, playerStats: madeUpStats() }), null);
  });

  test("an unknown part name is a violation, never another part's rules", () => {
    assert.equal(partViolation("lineups", madeUpStats()), "lineups: unknown part");
  });

  test("accepts the player statistics example in docs/scraper-backend-contract.md", () => {
    const example = contractExample("contract-example-player-stats");
    assert.equal(example.playerStats.status, "OK");
    assert.equal(payloadViolation(example), null);
  });

  test('a FAILED part\'s detail is cleaned: ≤ 500 chars, no control characters, never ""', () => {
    assert.equal(failedPart("TIMEOUT", "").detail, null);
    assert.equal(failedPart("TIMEOUT", "a\nb").detail, "a b");
    assert.equal(failedPart("TIMEOUT", "x".repeat(600)).detail.length, 500);
  });
});

/** A made-up game-details game (never the fixtures' names or ids). */
function madeUpGame(gameId, changes = {}) {
  return {
    gameId,
    date: "2026-09-05",
    kickoffTime: null,
    competition: "ליגה א' דרום",
    round: 1,
    ownSide: "AWAY",
    homeTeamName: "קבוצה א",
    awayTeamName: "קבוצה ב",
    homeScore: 2,
    awayScore: 1,
    players: [
      {
        playerId: 920001,
        name: "שם בדוי",
        shirtNumber: 1,
        role: "STARTER",
        goalkeeper: true,
        captain: false,
        events: [{ kind: "GOAL", minute: null }],
      },
      {
        playerId: 920002,
        name: null,
        shirtNumber: null,
        role: "UNUSED",
        goalkeeper: false,
        captain: false,
        events: [],
      },
    ],
    ...changes,
  };
}

const detailsPayload = (gameDetails) => ({
  runId: null,
  teamId: 1,
  leagueId: 2,
  seasonId: 3,
  gameDetails,
});
const okDetails = (games, failures = []) => ({
  status: "OK",
  fetchedAt: "2026-10-11T09:00:20Z",
  games,
  failures,
});

describe("local contract check: game details (KAN-80)", () => {
  test("cupGames is the fifth part (KAN-85)", () => {
    assert.deepEqual(PARTS, ["fixtures", "standings", "playerStats", "gameDetails", "cupGames"]);
  });

  test("accepts the game details example in docs/scraper-backend-contract.md", () => {
    const example = contractExample("contract-example-game-details");
    assert.equal(example.gameDetails.status, "OK");
    assert.equal(payloadViolation(example), null);
    assert.deepEqual(enforceContract(example), example);
  });

  test("OK with empty games and failures; a FAILED part", () => {
    assert.equal(payloadViolation(detailsPayload(okDetails([], []))), null);
    const failed = failedPart("SOURCE_BLOCKED", "not attempted");
    assert.equal(payloadViolation(detailsPayload(failed)), null);
  });

  const player = (changes) => [{ ...madeUpGame(1).players[0], ...changes }];
  const gameRules = {
    "round 0": [{ round: 0 }, "game.round: must be an integer in 1..99"],
    "an unknown ownSide": [{ ownSide: "home" }, "game.ownSide: unknown value"],
    "home = away": [
      { awayTeamName: " קבוצה א " },
      "game: homeTeamName must differ from awayTeamName",
    ],
    "a score of 100": [{ homeScore: 100 }, "game.homeScore: must be an integer in 0..99"],
    "an unreal date": [{ date: "2026-02-30" }, "game.date: must be a real yyyy-MM-dd date"],
    "a kickoffTime of 24:00": [{ kickoffTime: "24:00" }, "game.kickoffTime: must be HH:mm"],
    "no players": [{ players: [] }, "game.players: must have 1..40 players"],
    "41 players": [
      {
        players: Array.from({ length: 41 }, (_, i) => ({
          ...madeUpGame(1).players[1],
          playerId: 1 + i,
        })),
      },
      "game.players: must have 1..40 players",
    ],
    "a duplicate playerId": [
      { players: [madeUpGame(1).players[0], madeUpGame(1).players[0]] },
      "game.players[1].playerId: must be unique",
    ],
    "a name of 101 characters": [
      { players: player({ name: "א".repeat(101) }) },
      "game.players[0].name: must be 1..100 characters",
    ],
    "a blank name": [
      { players: player({ name: "  " }) },
      "game.players[0].name: must be 1..100 characters",
    ],
    "a shirt number of 100": [
      { players: player({ shirtNumber: 100 }) },
      "game.players[0].shirtNumber: must be an integer in 0..99",
    ],
    "an unknown role": [
      { players: player({ role: "BENCH" }) },
      "game.players[0].role: unknown value",
    ],
    "a null captain": [
      { players: player({ captain: null }) },
      "game.players[0].captain: must be a boolean",
    ],
    "an unknown event kind": [
      { players: player({ events: [{ kind: "OWN_GOAL", minute: 3 }] }) },
      "game.players[0].events[0].kind: unknown value",
    ],
    "a minute of -1": [
      { players: player({ events: [{ kind: "GOAL", minute: -1 }] }) },
      "game.players[0].events[0].minute: must be an integer in 0..150",
    ],
    "11 events": [
      { players: player({ events: Array(11).fill({ kind: "YELLOW", minute: 1 }) }) },
      "game.players[0].events: must be a list of at most 10",
    ],
    "an unknown property": [{ venue: "x" }, "game: unknown property"],
  };
  for (const [name, [changes, rule]] of Object.entries(gameRules)) {
    test(`a game with ${name} is moved to failures; the other game stays`, () => {
      const good = madeUpGame(5);
      const payload = detailsPayload(
        okDetails([madeUpGame(4, changes), good], [gameFailure(6, "TIMEOUT")]),
      );
      assert.equal(payloadViolation(payload), `gameDetails.games[0]${rule.slice(4)}`);
      const result = enforceContract(payload);
      assert.deepEqual(result.gameDetails, {
        ...payload.gameDetails,
        games: [good],
        failures: [
          { gameId: 6, reason: "TIMEOUT", detail: null },
          { gameId: 4, reason: "PARSE_ERROR", detail: `contract check: ${rule}` },
        ],
      });
      assert.equal(payloadViolation(result), null);
    });
  }

  test("a demoted game's detail never carries a value", () => {
    const name = "שם ארוך מאוד ".repeat(10);
    const payload = detailsPayload(okDetails([madeUpGame(4, { players: player({ name }) })]));
    const detail = enforceContract(payload).gameDetails.failures[0].detail;
    assert.ok(!detail.includes("שם"), detail);
    assert.ok(!detail.includes("920001"), detail);
  });

  const partRules = {
    "no fetchedAt": [{ fetchedAt: undefined }, "gameDetails.fetchedAt: is required"],
    "a bad fetchedAt": [
      { fetchedAt: "yesterday" },
      "gameDetails.fetchedAt: must be an ISO-8601 instant with an offset",
    ],
    "no failures": [{ failures: undefined }, "gameDetails.failures: is required"],
    "games not a list": [{ games: null }, "gameDetails.games: must be a list of at most 10"],
    "11 failures": [
      { failures: Array.from({ length: 11 }, (_, i) => gameFailure(100 + i, "TIMEOUT")) },
      "gameDetails.failures: must be a list of at most 10",
    ],
    "a failure with an unknown reason": [
      { failures: [{ gameId: 9, reason: "GONE", detail: null }] },
      "gameDetails.failures[0].reason: unknown reason",
    ],
    "a failure without a gameId": [
      { failures: [{ reason: "TIMEOUT", detail: null }] },
      "gameDetails.failures[0].gameId: is required",
    ],
    "a gameId in games and failures": [
      { failures: [gameFailure(4, "TIMEOUT")] },
      "gameDetails: gameId must be unique across games and failures",
    ],
    "a game without a valid gameId": [
      { games: [madeUpGame(0)] },
      "gameDetails.games[0].gameId: must be an integer in 1..2147483647",
    ],
    "a null game": [{ games: [null] }, "gameDetails.games[0]: must be an object"],
    "an unknown property": [{ storedGameIds: [] }, "gameDetails: unknown property"],
  };
  for (const [name, [changes, rule]] of Object.entries(partRules)) {
    test(`part-level: ${name} → the whole part FAILED PARSE_ERROR`, () => {
      const part = { ...okDetails([madeUpGame(4)]), ...changes };
      for (const key of Object.keys(part)) if (part[key] === undefined) delete part[key];
      const result = enforceContract(detailsPayload(part));
      assert.deepEqual(result.gameDetails, failedPart("PARSE_ERROR", `contract check: ${rule}`));
    });
  }

  test("a game-details violation never touches the other parts", async () => {
    const payload = await fixturePayload(JOB_ALL);
    const broken = {
      ...payload,
      gameDetails: okDetails([madeUpGame(4)], [gameFailure(4, "TIMEOUT")]),
    };
    const result = enforceContract(broken);
    assert.equal(result.gameDetails.status, "FAILED");
    for (const name of ["fixtures", "standings", "playerStats"]) {
      assert.deepEqual(result[name], payload[name], name);
    }
  });
});

describe("local contract check: cup games (KAN-85)", () => {
  const example = () => structuredClone(contractExample("contract-example-cup-games"));
  const game = () => structuredClone(example().cupGames.competitions[0].games[0]);
  const ids = { runId: null, teamId: 7617, leagueId: 62, seasonId: 28 };
  const okPart = (competitions) => ({
    status: "OK",
    fetchedAt: "2026-10-11T09:00:30Z",
    competitions,
  });
  const stateCup = (games) => ({ competition: "STATE_CUP", status: "OK", games });
  const payload = (cupGames, extra = {}) => ({ ...ids, ...extra, cupGames });

  test("the cup example in docs/scraper-backend-contract.md passes unchanged", () => {
    const doc = example();
    assert.equal(payloadViolation(doc), null);
    assert.deepEqual(enforceContract(doc), doc);
  });

  test("cupGames alone is a payload; OK with an empty list; a FAILED part; a FAILED competition", () => {
    assert.equal(payloadViolation(payload(okPart([stateCup([])]))), null);
    assert.equal(payloadViolation(payload(failedPart("TIMEOUT", "not attempted"))), null);
    const failed = { competition: "STATE_CUP", ...failedPart("SOURCE_ERROR", "500 from source") };
    assert.equal(payloadViolation(payload(okPart([failed]))), null);
    assert.equal(
      payloadViolation(
        payload(okPart([{ competition: "STATE_CUP", status: "FAILED", reason: "TIMEOUT" }])),
      ),
      null,
    );
  });

  // Each CupGame rule: the change, the rule's message relative to the competition.
  const gameRules = {
    "a missing field": [(g) => delete g.venue, "competition.games[0].venue: is required"],
    "an unknown field": [(g) => (g.result = "W"), "competition.games[0]: unknown property"],
    "gameId 0": [
      (g) => (g.gameId = 0),
      "competition.games[0].gameId: must be an integer in 1..2147483647",
    ],
    "gameId over int max": [
      (g) => (g.gameId = 2147483648),
      "competition.games[0].gameId: must be an integer in 1..2147483647",
    ],
    "a blank round": [
      (g) => (g.round = "  "),
      "competition.games[0].round: must be 1..100 characters",
    ],
    "a round over 100": [
      (g) => (g.round = "א".repeat(101)),
      "competition.games[0].round: must be 1..100 characters",
    ],
    "a control character in a name": [
      (g) => (g.homeTeamName = "א\u0007ב"),
      "competition.games[0].homeTeamName: must not contain control characters",
    ],
    "a name over 100": [
      (g) => (g.awayTeamName = "א".repeat(101)),
      "competition.games[0].awayTeamName: must be 1..100 characters",
    ],
    "home = away after trimming": [
      (g) => (g.awayTeamName = ` ${g.homeTeamName} `),
      "competition.games[0]: homeTeamName must differ from awayTeamName",
    ],
    "an unreal date": [
      (g) => (g.date = "2026-02-30"),
      "competition.games[0].date: must be a real yyyy-MM-dd date",
    ],
    "kickoff not HH:mm": [
      (g) => (g.kickoffTime = "7:00"),
      "competition.games[0].kickoffTime: must be HH:mm",
    ],
    "an unknown ownSide": [
      (g) => (g.ownSide = "home"),
      "competition.games[0].ownSide: unknown value",
    ],
    "an empty venue": [
      (g) => (g.venue = ""),
      "competition.games[0].venue: must be 1..200 characters",
    ],
    "a venue over 200": [
      (g) => (g.venue = "א".repeat(201)),
      "competition.games[0].venue: must be 1..200 characters",
    ],
    "one 90-minute score": [
      (g) => (g.awayScore = null),
      "competition.games[0]: homeScore and awayScore: both or neither",
    ],
    "a score of 100": [
      (g) => (g.homeScore = 100),
      "competition.games[0].homeScore: must be an integer in 0..99",
    ],
    "a score as a string": [
      (g) => (g.awayScore = "0"),
      "competition.games[0].awayScore: must be an integer in 0..99",
    ],
    "one extra-time score": [
      (g) => (g.extraTimeHomeScore = 2),
      "competition.games[0]: extraTimeHomeScore and extraTimeAwayScore: both or neither",
    ],
    "extra time as a raw string": [
      (g) => Object.assign(g, { extraTimeHomeScore: "2-3", extraTimeAwayScore: "x" }),
      "competition.games[0].extraTimeHomeScore: must be an integer in 0..99",
    ],
    "one penalties score": [
      (g) => (g.penaltiesAwayScore = 4),
      "competition.games[0]: penaltiesHomeScore and penaltiesAwayScore: both or neither",
    ],
    "a penalties score of -1": [
      (g) => Object.assign(g, { penaltiesHomeScore: -1, penaltiesAwayScore: 1 }),
      "competition.games[0].penaltiesHomeScore: must be an integer in 0..99",
    ],
    "extra time without the 90 minutes": [
      (g) =>
        Object.assign(g, {
          homeScore: null,
          awayScore: null,
          extraTimeHomeScore: 1,
          extraTimeAwayScore: 0,
        }),
      "competition.games[0]: extra time or penalties need homeScore and awayScore",
    ],
    "penalties without the 90 minutes": [
      (g) =>
        Object.assign(g, {
          homeScore: null,
          awayScore: null,
          penaltiesHomeScore: 4,
          penaltiesAwayScore: 3,
        }),
      "competition.games[0]: extra time or penalties need homeScore and awayScore",
    ],
  };
  for (const [name, [change, rule]] of Object.entries(gameRules)) {
    test(`${name}: only that competition becomes FAILED PARSE_ERROR`, () => {
      const broken = game();
      change(broken);
      const before = payload(okPart([stateCup([game(), broken])]), {
        fixtures: { status: "FAILED", reason: "TIMEOUT", detail: null },
      });
      const after = enforceContract(before);
      assert.deepEqual(after.cupGames, {
        status: "OK",
        fetchedAt: "2026-10-11T09:00:30Z",
        competitions: [
          {
            competition: "STATE_CUP",
            status: "FAILED",
            reason: "PARSE_ERROR",
            detail: `contract check: ${rule.replace("games[0]", "games[1]")}`,
          },
        ],
      });
      assert.deepEqual(after.fixtures, before.fixtures);
      assert.equal(payloadViolation(after), null);
      assert.ok(payloadViolation(before).startsWith("cupGames.competitions[0].games[1]"));
    });
  }

  test("accepted on purpose: penalties without extra time, an unequal shootout, extra time < 90 min", () => {
    const g = game();
    Object.assign(g, {
      extraTimeHomeScore: null,
      extraTimeAwayScore: null,
      penaltiesHomeScore: 5,
      penaltiesAwayScore: 5,
    });
    const h = {
      ...game(),
      homeScore: 3,
      awayScore: 3,
      extraTimeHomeScore: 1,
      extraTimeAwayScore: 0,
    };
    assert.equal(payloadViolation(payload(okPart([stateCup([g, h])]))), null);
  });

  test("a duplicate game is not the scraper's to reject (the backend's DUPLICATE_GAME)", () => {
    assert.equal(payloadViolation(payload(okPart([stateCup([game(), game()])]))), null);
  });

  test("over 50 games demotes the competition only", () => {
    const after = enforceContract(payload(okPart([stateCup(Array.from({ length: 51 }, game))])));
    assert.equal(after.cupGames.status, "OK");
    assert.deepEqual(
      after.cupGames.competitions[0].detail,
      "contract check: competition.games: must be a list of at most 50",
    );
  });

  const partRules = {
    "a duplicate competition": [
      okPart([stateCup([]), stateCup([])]),
      "cupGames.competitions[1].competition: must appear at most once",
    ],
    "no competitions": [okPart([]), "cupGames.competitions: must have 1..5 competitions"],
    "an unknown competition": [
      okPart([{ competition: "TOTO_CUP", status: "OK", games: [] }]),
      "cupGames.competitions[0].competition: unknown value",
    ],
    "a null competition": [okPart([null]), "cupGames.competitions[0]: must be an object"],
    "OK with a reason": [
      { ...okPart([stateCup([])]), reason: "TIMEOUT" },
      "cupGames: unknown property",
    ],
    "no fetchedAt": [
      { status: "OK", competitions: [stateCup([])] },
      "cupGames.fetchedAt: is required",
    ],
    "a FAILED part without a reason": [{ status: "FAILED" }, "cupGames.reason: is required"],
  };
  for (const [name, [part, rule]] of Object.entries(partRules)) {
    test(`${name}: the whole part becomes FAILED PARSE_ERROR, the others stay`, () => {
      const before = payload(part, {
        standings: { status: "FAILED", reason: "TIMEOUT", detail: null },
      });
      assert.equal(payloadViolation(before), rule);
      const after = enforceContract(before);
      assert.deepEqual(after.cupGames, failedPart("PARSE_ERROR", `contract check: ${rule}`));
      assert.deepEqual(after.standings, before.standings);
    });
  }

  test("a FAILED competition with an unknown reason, or with games, fails the part", () => {
    const unknown = okPart([{ competition: "STATE_CUP", status: "FAILED", reason: "GONE" }]);
    assert.equal(
      payloadViolation(payload(unknown)),
      "cupGames.competitions[0].reason: unknown reason",
    );
    const withGames = okPart([
      { competition: "STATE_CUP", status: "FAILED", reason: "TIMEOUT", games: [] },
    ]);
    assert.equal(
      payloadViolation(payload(withGames)),
      "cupGames.competitions[0]: unknown property",
    );
  });
});
