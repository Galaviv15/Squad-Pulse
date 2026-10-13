// Local check of a result payload against docs/scraper-backend-contract.md, before it's posted.
// The backend rejects the whole request (400) on any violation, which would also lose the other
// parts, so an `OK` part that breaks a rule is turned into `FAILED PARSE_ERROR` here. Each rule
// mirrors backend/.../scrapingintegration/ScrapeResultRequest.java, which is the source of truth.

import { hasControlCharacter } from "./text.js";

export const REASONS = ["SOURCE_BLOCKED", "TIMEOUT", "SOURCE_ERROR", "PARSE_ERROR"];
export const MAX_DETAIL_LENGTH = 500;
const JAVA_INT_MAX = 2147483647;

export const PARTS = ["fixtures", "standings", "playerStats"];
const TOP_KEYS = ["runId", "teamId", "leagueId", "seasonId", ...PARTS];
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
const PLAYER_STATS_KEYS = ["status", "fetchedAt", "players"];
const PLAYER_COUNTS = [
  "games",
  "goals",
  "yellowCardsLeagueAndCup",
  "yellowCardsToto",
  "redCards",
  "starts",
  "cameOnAsSub",
  "subbedOff",
];
const PLAYER_KEYS = ["playerId", "name", ...PLAYER_COUNTS, "minutes"];

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

/**
 * PlayerStatsPart / PlayerStatsRow (KAN-72): 1–80 players, none null; every field required, no
 * other; playerId 1..int max and unique; name 1–100 after trimming, no control characters; counts
 * 0..200, minutes 0..20000; games = starts + cameOnAsSub. Minutes are never checked against games.
 * A violation's path names the row by index, never by its name or playerId.
 */
function validatePlayerStats(part, path) {
  keys(part, PLAYER_STATS_KEYS, path);
  instant(part.fetchedAt, `${path}.fetchedAt`);
  if (!Array.isArray(part.players) || part.players.length < 1 || part.players.length > 80) {
    fail(`${path}.players`, "must have 1..80 players");
  }
  const playerIds = new Set();
  part.players.forEach((player, i) => {
    const at = `${path}.players[${i}]`;
    keys(player, PLAYER_KEYS, at);
    int(player.playerId, `${at}.playerId`, 1, JAVA_INT_MAX);
    text(player.name, `${at}.name`, 100);
    for (const key of PLAYER_COUNTS) int(player[key], `${at}.${key}`, 0, 200);
    int(player.minutes, `${at}.minutes`, 0, 20000);
    if (player.starts + player.cameOnAsSub !== player.games) {
      fail(at, "starts + cameOnAsSub must equal games");
    }
    if (playerIds.has(player.playerId)) fail(`${at}.playerId`, "must be unique");
    playerIds.add(player.playerId);
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
    else if (name === "standings") validateStandings(part, name);
    else if (name === "playerStats") validatePlayerStats(part, name);
    else fail(name, "unknown part");
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
    if (PARTS.every((name) => payload[name] == null)) {
      fail("request", "needs at least one part");
    }
  } catch (error) {
    if (error instanceof Violation) return error.message;
    throw error;
  }
  for (const name of PARTS) {
    const violation = partViolation(name, payload[name]);
    if (violation !== null) return violation;
  }
  return null;
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
  for (const name of PARTS) {
    const violation = partViolation(name, result[name]);
    if (violation !== null) {
      result[name] = failedPart("PARSE_ERROR", `contract check: ${violation}`);
    }
  }
  return result;
}
