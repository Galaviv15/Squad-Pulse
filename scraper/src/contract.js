// Local check of a result payload against docs/scraper-backend-contract.md, before it's posted.
// The backend rejects the whole request (400) on any violation, which would also lose the other
// part, so an `OK` part that breaks a rule is turned into `FAILED PARSE_ERROR` here.

import { hasControlCharacter } from "./text.js";

export const REASONS = ["SOURCE_BLOCKED", "TIMEOUT", "SOURCE_ERROR", "PARSE_ERROR"];
export const MAX_DETAIL_LENGTH = 500;
const JAVA_INT_MAX = 2147483647;

const TOP_KEYS = ["runId", "teamId", "leagueId", "seasonId", "fixtures", "standings"];
const FAILED_KEYS = ["status", "reason", "detail"];
const FIXTURES_KEYS = ["status", "fetchedAt", "games"];
const GAME_KEYS = [
  "gameId",
  "date",
  "kickoffTime",
  "homeTeamName",
  "awayTeamName",
  "venue",
  "homeScore",
  "awayScore",
];
const STANDINGS_KEYS = ["status", "fetchedAt", "round", "rows"];
const ROW_KEYS = [
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
];

class Violation extends Error {}

function fail(path, rule) {
  throw new Violation(`${path}: ${rule}`);
}

function isObject(value) {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function keys(value, allowed, path, required = allowed) {
  if (!isObject(value)) fail(path, "must be an object");
  for (const key of Object.keys(value)) {
    if (!allowed.includes(key)) fail(path, "unknown property");
  }
  for (const key of required) {
    if (!(key in value)) fail(`${path}.${key}`, "is required");
  }
}

function int(value, path, min, max) {
  if (!Number.isInteger(value) || value < min || value > max) {
    fail(path, `must be an integer in ${min}..${max}`);
  }
}

function text(value, path, maxLength) {
  if (typeof value !== "string") fail(path, "must be a string");
  if (hasControlCharacter(value)) fail(path, "must not contain control characters");
  const length = value.trim().length;
  if (length < 1 || length > maxLength) fail(path, `must be 1..${maxLength} characters`);
}

function nullableText(value, path, maxLength) {
  if (value !== null) text(value, path, maxLength);
}

function instant(value, path) {
  if (
    typeof value !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/.test(value) ||
    Number.isNaN(Date.parse(value))
  ) {
    fail(path, "must be an ISO-8601 instant with an offset");
  }
}

function isoDate(value, path) {
  const match = typeof value === "string" && /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  const date = match && new Date(Date.UTC(+match[1], +match[2] - 1, +match[3]));
  if (!date || date.toISOString().slice(0, 10) !== value)
    fail(path, "must be a real yyyy-MM-dd date");
}

function validateFixtures(part, path) {
  keys(part, FIXTURES_KEYS, path);
  instant(part.fetchedAt, `${path}.fetchedAt`);
  if (!Array.isArray(part.games) || part.games.length < 1 || part.games.length > 100) {
    fail(`${path}.games`, "must have 1..100 games");
  }
  part.games.forEach((game, i) => {
    const at = `${path}.games[${i}]`;
    keys(game, GAME_KEYS, at);
    if (game.gameId !== null) int(game.gameId, `${at}.gameId`, 1, JAVA_INT_MAX);
    isoDate(game.date, `${at}.date`);
    if (game.kickoffTime !== null && !/^([01]\d|2[0-3]):[0-5]\d$/.test(game.kickoffTime)) {
      fail(`${at}.kickoffTime`, "must be HH:mm");
    }
    text(game.homeTeamName, `${at}.homeTeamName`, 100);
    text(game.awayTeamName, `${at}.awayTeamName`, 100);
    if (game.homeTeamName.trim() === game.awayTeamName.trim()) {
      fail(at, "homeTeamName must differ from awayTeamName");
    }
    nullableText(game.venue, `${at}.venue`, 200);
    if ((game.homeScore === null) !== (game.awayScore === null)) {
      fail(at, "both scores or neither");
    }
    if (game.homeScore !== null) {
      int(game.homeScore, `${at}.homeScore`, 0, 99);
      int(game.awayScore, `${at}.awayScore`, 0, 99);
    }
  });
}

function validateStandings(part, path) {
  keys(part, STANDINGS_KEYS, path);
  instant(part.fetchedAt, `${path}.fetchedAt`);
  int(part.round, `${path}.round`, 1, JAVA_INT_MAX);
  if (!Array.isArray(part.rows) || part.rows.length < 2 || part.rows.length > 40) {
    fail(`${path}.rows`, "must have 2..40 rows");
  }
  const teamIds = new Set();
  const teamNames = new Set();
  part.rows.forEach((row, i) => {
    const at = `${path}.rows[${i}]`;
    keys(row, ROW_KEYS, at);
    int(row.position, `${at}.position`, 1, part.rows.length);
    int(row.teamId, `${at}.teamId`, 1, JAVA_INT_MAX);
    text(row.teamName, `${at}.teamName`, 100);
    for (const key of ["played", "won", "drawn", "lost"]) int(row[key], `${at}.${key}`, 0, 99);
    if (row.won + row.drawn + row.lost !== row.played) {
      fail(at, "won + drawn + lost must equal played");
    }
    int(row.goalsFor, `${at}.goalsFor`, 0, 999);
    int(row.goalsAgainst, `${at}.goalsAgainst`, 0, 999);
    int(row.points, `${at}.points`, -99, 999);
    if (teamIds.has(row.teamId)) fail(`${at}.teamId`, "must be unique in the table");
    teamIds.add(row.teamId);
    const name = row.teamName.trim();
    if (teamNames.has(name)) fail(`${at}.teamName`, "must be unique in the table");
    teamNames.add(name);
  });
}

function validateFailed(part, path) {
  keys(part, FAILED_KEYS, path, ["status", "reason"]);
  if (!REASONS.includes(part.reason)) fail(`${path}.reason`, "unknown reason");
  if (part.detail !== undefined) nullableText(part.detail, `${path}.detail`, MAX_DETAIL_LENGTH);
}

/** The first rule a part breaks (`"path: rule"`), or `null` if it follows the contract. */
export function partViolation(name, part) {
  try {
    if (part === null || part === undefined) return null;
    if (!isObject(part)) fail(name, "must be an object");
    if (part.status === "FAILED") validateFailed(part, name);
    else if (part.status !== "OK") fail(`${name}.status`, "must be OK or FAILED");
    else if (name === "fixtures") validateFixtures(part, name);
    else validateStandings(part, name);
    return null;
  } catch (error) {
    if (error instanceof Violation) return error.message;
    throw error;
  }
}

/** The first rule the whole request breaks, or `null`. */
export function payloadViolation(payload) {
  try {
    keys(payload, TOP_KEYS, "request", ["teamId", "leagueId", "seasonId"]);
    if (payload.runId !== null && payload.runId !== undefined) {
      if (typeof payload.runId !== "string" || payload.runId.length > 100) {
        fail("runId", "must be at most 100 characters");
      }
      if (hasControlCharacter(payload.runId)) fail("runId", "must not contain control characters");
    }
    for (const key of ["teamId", "leagueId", "seasonId"]) int(payload[key], key, 1, JAVA_INT_MAX);
    if (payload.fixtures == null && payload.standings == null) {
      fail("request", "needs at least one part");
    }
  } catch (error) {
    if (error instanceof Violation) return error.message;
    throw error;
  }
  return (
    partViolation("fixtures", payload.fixtures) ?? partViolation("standings", payload.standings)
  );
}

/** A `FAILED` part. `detail` is cleaned to the contract's rules (never `""`). */
export function failedPart(reason, detail = null) {
  let clean = detail === null ? null : detail.replace(/[\u0000-\u001f\u007f-\u009f]/g, " ").trim();
  if (clean !== null && clean.length > MAX_DETAIL_LENGTH) clean = clean.slice(0, MAX_DETAIL_LENGTH);
  return { status: "FAILED", reason, detail: clean === "" ? null : clean };
}

/** Turns every `OK` part that breaks the contract into `FAILED PARSE_ERROR` (first rule as detail). */
export function enforceContract(payload) {
  const result = { ...payload };
  for (const name of ["fixtures", "standings"]) {
    const violation = partViolation(name, result[name]);
    if (violation !== null) {
      result[name] = failedPart("PARSE_ERROR", `contract check: ${violation}`);
    }
  }
  return result;
}
