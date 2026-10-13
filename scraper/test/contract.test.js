import assert from "node:assert/strict";
import { describe, test } from "node:test";
import {
  PARTS,
  enforceContract,
  failedPart,
  partViolation,
  payloadViolation,
} from "../src/contract.js";
import { runScrape } from "../src/scrape.js";
import { createMemoryLogger } from "../src/log.js";
import { JOB, JOB_ALL, OK_SCRIPT, contractExample, fakeClock, fakeSource } from "./helpers.js";

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
  top: ["runId", "teamId", "leagueId", "seasonId", "fixtures", "standings", "playerStats"],
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
    for (const part of PARTS) assert.equal(payload[part].status, "OK", part);
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
        for (const other of PARTS.filter((name) => name !== part)) {
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
