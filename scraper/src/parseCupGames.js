// The team page's full club name and the team's State Cup list (KAN-76 findings §1–§3, KAN-85).
//
// `GetTeamNatCupData` doesn't answer with the `ResponseData` envelope (asmx.js): it's a .NET
// `string` serialized as XML, whose text is a JSON object `{StandingTable, Comments, Games}`;
// `Games` is the list's HTML (or `null` for no list). Every message is a fixed text, never a value
// from the page: a team name or a round must not reach a `detail` or a log line.

import { load } from "cheerio";
import { cleanText, parseDate, parseTime, queryParameter } from "./parseFixtures.js";
import { ParseError } from "./text.js";

export const STATE_CUP = "STATE_CUP";
export const MAX_CUP_GAMES = 50;
const JAVA_INT_MAX = 2147483647;
const CELLS = 8;
const SCORE = /^(\d{1,2})-(\d{1,2})$/;
// The first team name ends with U+00A0, a hyphen and a space (`הפועל רהט&nbsp;- `).
const HOME_SUFFIX = /\u00a0-\s*$/;

/**
 * The `string` envelope. → `{games, extras}`: `games` the list's HTML or `null` (no list), `extras`
 * the names of the other keys that aren't `null` (`StandingTable`, `Comments`: never parsed, only
 * reported so the first one can be captured). `responseName` names the endpoint in a `ParseError`.
 */
export function readCupEnvelope(xml, responseName) {
  const envelope = load(xml, { xml: true });
  const root = envelope.root().children();
  if (root.length !== 1 || root[0].name !== "string") {
    throw new ParseError(`no string root element in the ${responseName} response`);
  }
  let json;
  try {
    json = JSON.parse(root.text());
  } catch {
    throw new ParseError(`the ${responseName} response isn't JSON`);
  }
  if (typeof json !== "object" || json === null || Array.isArray(json) || !("Games" in json)) {
    throw new ParseError(`no Games in the ${responseName} response`);
  }
  if (json.Games !== null && typeof json.Games !== "string") {
    throw new ParseError(`Games isn't a string in the ${responseName} response`);
  }
  const extras = ["StandingTable", "Comments"].filter((key) => json[key] != null);
  return { games: json.Games, extras };
}

/** Our full club name: the team page's one `h1.page_main_title span.big`, trimmed, non-empty. */
export function parseOwnName(html) {
  const $ = load(html);
  const big = $("h1.page_main_title span.big");
  if (big.length !== 1) throw new ParseError("expected one h1.page_main_title span.big");
  const name = cleanText(big.text());
  if (name === "") throw new ParseError("an empty club name in the team page h1");
  return name;
}

/**
 * The State Cup list → `{games, extras}`, `games` in the contract's CupGame shape (scores home
 * first), `extras` as {@link readCupEnvelope}'s. `Games: null` is an empty list. Rows are read by
 * position, never by their `sr-only` labels (the first two are swapped on the site). Duplicates
 * aren't checked here: the backend rejects that competition alone (`DUPLICATE_GAME`).
 */
export function parseCupGames(xml, ownName) {
  const { games: html, extras } = readCupEnvelope(xml, "GetTeamNatCupData");
  if (html === null) return { games: [], extras };
  const $ = load(html);
  const table = $("div.table_view");
  if (table.length !== 1) throw new ParseError("expected one div.table_view in the cup list");
  const rows = table
    .find(".table_row")
    .toArray()
    .filter((row) => !$(row).hasClass("table_header_row"));
  if (rows.length > MAX_CUP_GAMES) {
    throw new ParseError(`more than ${MAX_CUP_GAMES} rows in the cup list`);
  }
  return { games: rows.map((row) => parseCupRow($, $(row), ownName)), extras };
}

function parseCupRow($, row, ownName) {
  const cells = row
    .children("div.table_col")
    .toArray()
    .map((cell) => $(cell));
  if (cells.length !== CELLS) throw new ParseError(`a cup row without ${CELLS} cells`);
  const round = value(cells[0]);
  if (round.length < 1 || round.length > 100) throw new ParseError("a cup round outside 1..100");
  const [homeTeamName, awayTeamName] = teams($, cells[2]);
  const homeOwn = homeTeamName === ownName;
  const awayOwn = awayTeamName === ownName;
  if (homeOwn === awayOwn) {
    throw new ParseError(`${homeOwn ? "both" : "neither"} cup row teams are our team`);
  }
  const venue = value(cells[3]);
  const [homeScore, awayScore] = score(cells[5], "result");
  const [extraTimeHomeScore, extraTimeAwayScore] = score(cells[6], "extra time");
  const [penaltiesHomeScore, penaltiesAwayScore] = score(cells[7], "penalties");
  return {
    gameId: gameId(row),
    round,
    date: fixed(() => parseDate(value(cells[1])), "an invalid date in a cup row"),
    kickoffTime: fixed(() => parseTime(value(cells[4])), "an invalid kickoff time in a cup row"),
    homeTeamName,
    awayTeamName,
    ownSide: homeOwn ? "HOME" : "AWAY",
    venue: venue === "" ? null : venue,
    homeScore,
    awayScore,
    extraTimeHomeScore,
    extraTimeAwayScore,
    penaltiesHomeScore,
    penaltiesAwayScore,
  };
}

/** Replaces a ParseError's message (which may quote the cell) with a fixed text. */
function fixed(read, message) {
  try {
    return read();
  } catch (error) {
    if (error instanceof ParseError) throw new ParseError(message);
    throw error;
  }
}

/** A cell's text without its `sr-only` label (an empty cell holds only the label). */
function value(cell) {
  const copy = cell.clone();
  copy.find(".sr-only").remove();
  return cleanText(copy.text());
}

/** Exactly two `span.team-name-text`; never a split on `-`, so a hyphenated name is safe. */
function teams($, cell) {
  const spans = cell.find("span.team-name-text").toArray();
  if (spans.length !== 2) throw new ParseError("a cup row without two team names");
  const [home, away] = spans.map((span) => $(span).text());
  const names = [cleanText(home.replace(HOME_SUFFIX, "")), cleanText(away)];
  if (names.includes("")) throw new ParseError("an empty team name in a cup row");
  return names;
}

/** `a-b`, away-first like the league's result → `[home, away]`; empty → `[null, null]`. */
function score(cell, what) {
  const text = value(cell);
  if (text === "") return [null, null];
  const match = SCORE.exec(text);
  if (!match) throw new ParseError(`a cup ${what} cell that isn't a score`);
  const [, away, home] = match;
  return [Number(home), Number(away)];
}

/** The row link's `game_id`; `null` without a link or for the site's `-1`. */
function gameId(row) {
  const href = row.attr("href");
  if (href === undefined) return null;
  const raw = queryParameter(href, "game_id");
  if (raw === null || raw === "-1") return null;
  if (!/^\d{1,10}$/.test(raw) || Number(raw) < 1 || Number(raw) > JAVA_INT_MAX) {
    throw new ParseError("an invalid game_id in a cup row");
  }
  return Number(raw);
}
