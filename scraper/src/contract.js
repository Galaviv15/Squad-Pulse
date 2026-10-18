// Local check of a result payload against docs/scraper-backend-contract.md, before it's posted.
// The backend rejects the whole request (400) on any violation, which would also lose the other
// parts, so an `OK` part that breaks a rule is turned into `FAILED PARSE_ERROR` here; a game-details
// game that breaks one is moved to that part's `failures` instead (KAN-80), and a cup competition
// whose games break one becomes that competition's `FAILED PARSE_ERROR` (KAN-85). Each rule mirrors
// backend/.../scrapingintegration/ScrapeResultRequest.java, which is the source of truth.

import { hasControlCharacter } from "./text.js";

export const REASONS = ["SOURCE_BLOCKED", "TIMEOUT", "SOURCE_ERROR", "PARSE_ERROR"];
export const MAX_DETAIL_LENGTH = 500;
const JAVA_INT_MAX = 2147483647;

export const PARTS = ["fixtures", "standings", "playerStats", "gameDetails", "cupGames"];
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
const GAME_DETAILS_KEYS = ["status", "fetchedAt", "games", "failures"];
const DETAILS_GAME_KEYS = [
  "gameId",
  "date",
  "kickoffTime",
  "competition",
  "round",
  "ownSide",
  "homeTeamName",
  "awayTeamName",
  "homeScore",
  "awayScore",
  "players",
];
const APPEARANCE_KEYS = [
  "playerId",
  "name",
  "shirtNumber",
  "role",
  "goalkeeper",
  "captain",
  "events",
];
const EVENT_KEYS = ["kind", "minute"];
const GAME_FAILURE_KEYS = ["gameId", "reason", "detail"];
const CUP_GAMES_KEYS = ["status", "fetchedAt", "competitions"];
const CUP_COMPETITION_OK_KEYS = ["competition", "status", "games"];
const CUP_COMPETITION_FAILED_KEYS = ["competition", "status", "reason", "detail"];
const CUP_GAME_KEYS = [
  "gameId",
  "round",
  "date",
  "kickoffTime",
  "homeTeamName",
  "awayTeamName",
  "ownSide",
  "venue",
  "homeScore",
  "awayScore",
  "extraTimeHomeScore",
  "extraTimeAwayScore",
  "penaltiesHomeScore",
  "penaltiesAwayScore",
];
const CUP_SCORE_PAIRS = [
  ["homeScore", "awayScore"],
  ["extraTimeHomeScore", "extraTimeAwayScore"],
  ["penaltiesHomeScore", "penaltiesAwayScore"],
];
// backend match.CupCompetition: only the State Cup today.
export const CUP_COMPETITIONS = ["STATE_CUP"];
const MAX_CUP_COMPETITIONS = 5;
const MAX_CUP_GAMES = 50;
const SIDES = ["HOME", "AWAY"];
const ROLES = ["STARTER", "SUBSTITUTE", "UNUSED"];
const EVENT_KINDS = ["GOAL", "YELLOW", "SECOND_YELLOW", "RED", "SUB_ON", "SUB_OFF"];
const MAX_DETAILS_GAMES = 10;

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

/**
 * GameDetailsPart (KAN-79): fetchedAt; games and failures both present, 0–10 each, none null; a
 * gameId at most once across the two lists. No count checks (starters, goalkeepers, goals vs
 * score, subs): real pages break them.
 */
function validateGameDetails(part, path) {
  keys(part, GAME_DETAILS_KEYS, path);
  instant(part.fetchedAt, `${path}.fetchedAt`);
  for (const list of ["games", "failures"]) {
    if (!Array.isArray(part[list]) || part[list].length > MAX_DETAILS_GAMES) {
      fail(`${path}.${list}`, `must be a list of at most ${MAX_DETAILS_GAMES}`);
    }
  }
  part.games.forEach((game, i) => validateDetailsGame(game, `${path}.games[${i}]`));
  part.failures.forEach((failure, i) => validateGameFailure(failure, `${path}.failures[${i}]`));
  const ids = new Set();
  [...part.games, ...part.failures].forEach((entry) => {
    if (ids.has(entry.gameId)) fail(`${path}`, "gameId must be unique across games and failures");
    ids.add(entry.gameId);
  });
}

/**
 * GameDetailsGame / GameAppearance / GameEvent. Every field present; kickoffTime, a player's name,
 * shirtNumber and an event's minute may be null. A violation's path names entries by index, never
 * by a name or id.
 */
function validateDetailsGame(game, at) {
  keys(game, DETAILS_GAME_KEYS, at);
  int(game.gameId, `${at}.gameId`, 1, JAVA_INT_MAX);
  isoDate(game.date, `${at}.date`);
  if (game.kickoffTime !== null && !/^([01]\d|2[0-3]):[0-5]\d$/.test(game.kickoffTime)) {
    fail(`${at}.kickoffTime`, "must be HH:mm");
  }
  text(game.competition, `${at}.competition`, 100);
  int(game.round, `${at}.round`, 1, 99);
  oneOf(game.ownSide, SIDES, `${at}.ownSide`);
  text(game.homeTeamName, `${at}.homeTeamName`, 100);
  text(game.awayTeamName, `${at}.awayTeamName`, 100);
  if (game.homeTeamName.trim() === game.awayTeamName.trim()) {
    fail(at, "homeTeamName must differ from awayTeamName");
  }
  int(game.homeScore, `${at}.homeScore`, 0, 99);
  int(game.awayScore, `${at}.awayScore`, 0, 99);
  if (!Array.isArray(game.players) || game.players.length < 1 || game.players.length > 40) {
    fail(`${at}.players`, "must have 1..40 players");
  }
  const playerIds = new Set();
  game.players.forEach((player, i) => {
    const p = `${at}.players[${i}]`;
    keys(player, APPEARANCE_KEYS, p);
    int(player.playerId, `${p}.playerId`, 1, JAVA_INT_MAX);
    nullableText(player.name, `${p}.name`, 100);
    if (player.shirtNumber !== null) int(player.shirtNumber, `${p}.shirtNumber`, 0, 99);
    oneOf(player.role, ROLES, `${p}.role`);
    for (const key of ["goalkeeper", "captain"]) {
      if (typeof player[key] !== "boolean") fail(`${p}.${key}`, "must be a boolean");
    }
    if (!Array.isArray(player.events) || player.events.length > 10) {
      fail(`${p}.events`, "must be a list of at most 10");
    }
    player.events.forEach((event, j) => {
      const e = `${p}.events[${j}]`;
      keys(event, EVENT_KEYS, e);
      oneOf(event.kind, EVENT_KINDS, `${e}.kind`);
      if (event.minute !== null) int(event.minute, `${e}.minute`, 0, 150);
    });
    if (playerIds.has(player.playerId)) fail(`${p}.playerId`, "must be unique");
    playerIds.add(player.playerId);
  });
}

function validateGameFailure(failure, at) {
  keys(failure, GAME_FAILURE_KEYS, at, ["gameId", "reason"]);
  int(failure.gameId, `${at}.gameId`, 1, JAVA_INT_MAX);
  if (!REASONS.includes(failure.reason)) fail(`${at}.reason`, "unknown reason");
  if (failure.detail !== undefined) nullableText(failure.detail, `${at}.detail`, MAX_DETAIL_LENGTH);
}

/**
 * CupGamesPart (KAN-84): `OK` → fetchedAt and 1–5 competitions, none null, each competition at
 * most once. A duplicate game within a competition is deliberately not checked (the backend
 * rejects that competition alone, `DUPLICATE_GAME`).
 */
function validateCupGames(part, path) {
  keys(part, CUP_GAMES_KEYS, path);
  instant(part.fetchedAt, `${path}.fetchedAt`);
  if (
    !Array.isArray(part.competitions) ||
    part.competitions.length < 1 ||
    part.competitions.length > MAX_CUP_COMPETITIONS
  ) {
    fail(`${path}.competitions`, `must have 1..${MAX_CUP_COMPETITIONS} competitions`);
  }
  const seen = new Set();
  part.competitions.forEach((entry, i) => {
    const at = `${path}.competitions[${i}]`;
    validateCupCompetition(entry, at);
    if (seen.has(entry.competition)) fail(`${at}.competition`, "must appear at most once");
    seen.add(entry.competition);
  });
}

/** CupCompetitionPart: `OK` → games (0–50, none null); `FAILED` → a known reason, no games. */
function validateCupCompetition(entry, at) {
  if (!isObject(entry)) fail(at, "must be an object");
  oneOf(entry.competition, CUP_COMPETITIONS, `${at}.competition`);
  if (entry.status === "FAILED") {
    keys(entry, CUP_COMPETITION_FAILED_KEYS, at, ["competition", "status", "reason"]);
    if (!REASONS.includes(entry.reason)) fail(`${at}.reason`, "unknown reason");
    if (entry.detail !== undefined) nullableText(entry.detail, `${at}.detail`, MAX_DETAIL_LENGTH);
    return;
  }
  if (entry.status !== "OK") fail(`${at}.status`, "must be OK or FAILED");
  keys(entry, CUP_COMPETITION_OK_KEYS, at);
  if (!Array.isArray(entry.games) || entry.games.length > MAX_CUP_GAMES) {
    fail(`${at}.games`, `must be a list of at most ${MAX_CUP_GAMES}`);
  }
  entry.games.forEach((game, i) => validateCupGame(game, `${at}.games[${i}]`));
}

/**
 * CupGame: every field present; gameId null or 1..int max; round and names 1–100 after trimming,
 * no control characters, names different; date yyyy-MM-dd; kickoffTime null or HH:mm; ownSide;
 * venue null or 1–200; each score pair 0–99, both or neither; extra time or penalties need the
 * 90-minute score. Nothing else across the scores (not extra time ≥ the 90 minutes, not an unequal
 * shootout). A violation's path names the game by index, never by a name or a round.
 */
function validateCupGame(game, at) {
  keys(game, CUP_GAME_KEYS, at);
  if (game.gameId !== null) int(game.gameId, `${at}.gameId`, 1, JAVA_INT_MAX);
  text(game.round, `${at}.round`, 100);
  isoDate(game.date, `${at}.date`);
  if (game.kickoffTime !== null && !/^([01]\d|2[0-3]):[0-5]\d$/.test(game.kickoffTime)) {
    fail(`${at}.kickoffTime`, "must be HH:mm");
  }
  text(game.homeTeamName, `${at}.homeTeamName`, 100);
  text(game.awayTeamName, `${at}.awayTeamName`, 100);
  if (game.homeTeamName.trim() === game.awayTeamName.trim()) {
    fail(at, "homeTeamName must differ from awayTeamName");
  }
  oneOf(game.ownSide, SIDES, `${at}.ownSide`);
  nullableText(game.venue, `${at}.venue`, 200);
  for (const [home, away] of CUP_SCORE_PAIRS) {
    if ((game[home] === null) !== (game[away] === null))
      fail(at, `${home} and ${away}: both or neither`);
    if (game[home] !== null) {
      int(game[home], `${at}.${home}`, 0, 99);
      int(game[away], `${at}.${away}`, 0, 99);
    }
  }
  const later = CUP_SCORE_PAIRS.slice(1).some(([home]) => game[home] !== null);
  if (later && game.homeScore === null) {
    fail(at, "extra time or penalties need homeScore and awayScore");
  }
}

function oneOf(value, allowed, path) {
  if (!allowed.includes(value)) fail(path, "unknown value");
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
    else if (name === "gameDetails") validateGameDetails(part, name);
    else if (name === "cupGames") validateCupGames(part, name);
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

/** A `detail` cleaned to the contract's rules: control characters → spaces, ≤ 500, never `""`. */
export function cleanDetail(detail) {
  let clean = detail === null ? null : detail.replace(/[\u0000-\u001f\u007f-\u009f]/g, " ").trim();
  if (clean !== null && clean.length > MAX_DETAIL_LENGTH) clean = clean.slice(0, MAX_DETAIL_LENGTH);
  return clean === "" ? null : clean;
}

/** A `FAILED` part. `detail` is cleaned to the contract's rules (never `""`). */
export function failedPart(reason, detail = null) {
  return { status: "FAILED", reason, detail: cleanDetail(detail) };
}

/** A game-details `failures` entry; `detail` cleaned like a part's. */
export function gameFailure(gameId, reason, detail = null) {
  return { gameId, reason, detail: cleanDetail(detail) };
}

/** The first rule one game-details game breaks, its path relative to the game; `null` if none. */
export function detailsGameViolation(game) {
  try {
    validateDetailsGame(game, "game");
    return null;
  } catch (error) {
    if (error instanceof Violation) return error.message;
    throw error;
  }
}

/**
 * Moves every game of an `OK` game-details part that breaks a rule to `failures`, as `PARSE_ERROR`
 * `contract check: <rule>`: the other games stay, and the backend counts the moved one as a failed
 * attempt (KAN-79). A game without a valid `gameId` can't be listed as a failure and stays, so the
 * whole part fails below, as does any other part-level violation.
 */
function demoteGames(part) {
  if (!isObject(part) || part.status !== "OK") return part;
  if (!Array.isArray(part.games) || !Array.isArray(part.failures)) return part;
  const games = [];
  const failures = [...part.failures];
  for (const game of part.games) {
    const violation = detailsGameViolation(game);
    const id = isObject(game) ? game.gameId : null;
    if (violation === null || !Number.isInteger(id) || id < 1 || id > JAVA_INT_MAX) {
      games.push(game);
    } else {
      failures.push(gameFailure(id, "PARSE_ERROR", `contract check: ${violation}`));
    }
  }
  return { ...part, games, failures };
}

/** The first rule one cup competition entry breaks, its path relative to it; `null` if none. */
export function cupCompetitionViolation(entry) {
  try {
    validateCupCompetition(entry, "competition");
    return null;
  } catch (error) {
    if (error instanceof Violation) return error.message;
    throw error;
  }
}

/**
 * Turns every `OK` cup competition whose own content breaks a rule into that competition's
 * `FAILED PARSE_ERROR` `contract check: <rule>` (the path relative to the competition): the other
 * competitions and parts stay. A competition that isn't an object or has an unknown `competition`
 * stays, so the whole part fails below, as does any other part-level violation (e.g. a duplicate
 * competition).
 */
function demoteCompetitions(part) {
  if (!isObject(part) || part.status !== "OK" || !Array.isArray(part.competitions)) return part;
  const competitions = part.competitions.map((entry) => {
    if (
      !isObject(entry) ||
      entry.status !== "OK" ||
      !CUP_COMPETITIONS.includes(entry.competition)
    ) {
      return entry;
    }
    const violation = cupCompetitionViolation(entry);
    if (violation === null) return entry;
    return {
      competition: entry.competition,
      ...failedPart("PARSE_ERROR", `contract check: ${violation}`),
    };
  });
  return { ...part, competitions };
}

/**
 * Turns every `OK` part that breaks the contract into `FAILED PARSE_ERROR` (first rule as detail),
 * after moving game-details games that break a rule to that part's `failures` and failing cup
 * competitions that break one on their own.
 */
export function enforceContract(payload) {
  const result = { ...payload };
  if (result.gameDetails) result.gameDetails = demoteGames(result.gameDetails);
  if (result.cupGames) result.cupGames = demoteCompetitions(result.cupGames);
  for (const name of PARTS) {
    const violation = partViolation(name, result[name]);
    if (violation !== null) {
      result[name] = failedPart("PARSE_ERROR", `contract check: ${violation}`);
    }
  }
  return result;
}
