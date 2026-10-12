import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { enforceContract, failedPart, payloadViolation } from "../src/contract.js";
import { runScrape } from "../src/scrape.js";
import { createMemoryLogger } from "../src/log.js";
import { JOB, OK_SCRIPT, contractExample, fakeClock, fakeSource } from "./helpers.js";

async function fixturePayload() {
  const clock = fakeClock();
  return runScrape(JOB, {
    source: fakeSource(clock, OK_SCRIPT()),
    clock,
    log: createMemoryLogger(),
    random: () => 0.5,
  });
}

const ALLOWED = {
  top: ["runId", "teamId", "leagueId", "seasonId", "fixtures", "standings"],
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

  test('no "" anywhere and no property outside the contract', async () => {
    const payload = await fixturePayload();
    walkStrings(payload, (s) => assert.notEqual(s.trim(), ""));
    assert.deepEqual(Object.keys(payload).sort(), [...ALLOWED.top].sort());
    assert.deepEqual(Object.keys(payload.fixtures).sort(), [...ALLOWED.fixtures].sort());
    assert.deepEqual(Object.keys(payload.standings).sort(), [...ALLOWED.standings].sort());
    for (const g of payload.fixtures.games)
      assert.deepEqual(Object.keys(g).sort(), [...ALLOWED.game].sort());
    for (const r of payload.standings.rows)
      assert.deepEqual(Object.keys(r).sort(), [...ALLOWED.row].sort());
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

  for (const [part, cases, other] of [
    ["fixtures", brokenFixtures, "standings"],
    ["standings", brokenStandings, "fixtures"],
  ]) {
    for (const [name, breakIt] of Object.entries(cases)) {
      test(`${part}: ${name} → FAILED PARSE_ERROR, the other part stays OK`, async () => {
        const payload = structuredClone(await fixturePayload());
        breakIt(payload);
        assert.notEqual(payloadViolation(payload), null);
        const enforced = enforceContract(payload);
        assert.equal(enforced[part].status, "FAILED");
        assert.equal(enforced[part].reason, "PARSE_ERROR");
        assert.match(enforced[part].detail, new RegExp(`^contract check: ${part}`));
        assert.equal(enforced[other].status, "OK");
        assert.equal(payloadViolation(enforced), null);
      });
    }
  }

  test('a FAILED part\'s detail is cleaned: ≤ 500 chars, no control characters, never ""', () => {
    assert.equal(failedPart("TIMEOUT", "").detail, null);
    assert.equal(failedPart("TIMEOUT", "a\nb").detail, "a b");
    assert.equal(failedPart("TIMEOUT", "x".repeat(600)).detail.length, 500);
  });
});
