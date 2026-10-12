// Parses the team-games page (findings sections 4, 6 and 9).

import { load } from "cheerio";
import { ParseError, excerpt, hasControlCharacter, normalizeText } from "./text.js";

const ROW_SELECTOR = 'section.games_table a.table_row[href*="game_id="]';
const MAX_GAMES = 100;

const LABELS = new Map([
  ["תאריך", "date"],
  ["משחק", "game"],
  ["אצטדיון", "venue"],
  ["שעה", "time"],
  ["תוצאה", "result"],
]);

/** Reads every column of a row by its `span.sr-only` label (never by position). */
export function readLabelledColumns($, row, labels) {
  const cells = new Map();
  $(row)
    .children("div.table_col")
    .each((_, col) => {
      const labelElement = $(col).find("span.sr-only").first();
      if (labelElement.length === 0) {
        throw new ParseError("missing column label");
      }
      const label = cleanText(labelElement.text());
      const key = labels.get(label);
      if (key === undefined) {
        throw new ParseError(`unknown column label: ${excerpt(label)}`);
      }
      if (cells.has(key)) {
        throw new ParseError(`duplicate column label: ${label}`);
      }
      const value = $(col).clone();
      value.find("span.sr-only").remove();
      cells.set(key, { text: cleanText(value.text()), element: $(col) });
    });
  for (const [label, key] of labels) {
    if (!cells.has(key)) {
      throw new ParseError(`missing column: ${label}`);
    }
  }
  return cells;
}

/** Normalizes page text; a control character left after that is a parse error. */
export function cleanText(raw) {
  const text = normalizeText(raw);
  if (hasControlCharacter(text)) {
    throw new ParseError("control character in the page text");
  }
  return text;
}

/** An integer query parameter of a link, e.g. `game_id`; `null` when absent. */
export function queryParameter(href, name) {
  return new URL(href, "https://invalid.example/").searchParams.get(name);
}

export function parseGamesPage(html) {
  const $ = load(html);
  const rows = $(ROW_SELECTOR).toArray();
  if (rows.length === 0) {
    throw new ParseError("no game rows on the games page");
  }
  if (rows.length > MAX_GAMES) {
    throw new ParseError(`more than ${MAX_GAMES} game rows on the games page`);
  }
  return rows.map((row) => parseGameRow($, row));
}

function parseGameRow($, row) {
  const cells = readLabelledColumns($, row, LABELS);
  const [homeTeamName, awayTeamName] = parseTeams(cells.get("game").text);
  const [homeScore, awayScore] = parseResult(cells.get("result").text);
  const venue = cells.get("venue").text;
  return {
    gameId: parseGameId($(row).attr("href")),
    date: parseDate(cells.get("date").text),
    kickoffTime: parseTime(cells.get("time").text),
    homeTeamName,
    awayTeamName,
    venue: venue === "" ? null : venue,
    homeScore,
    awayScore,
  };
}

function parseGameId(href) {
  const raw = queryParameter(href, "game_id");
  if (raw === "-1") {
    return null;
  }
  if (!/^\d{1,10}$/.test(raw ?? "") || Number(raw) < 1) {
    throw new ParseError(`invalid game_id: ${excerpt(raw)}`);
  }
  return Number(raw);
}

/** `dd/MM/yyyy` → `yyyy-MM-dd`, only for a real calendar date. */
export function parseDate(text) {
  const match = /^(\d{2})\/(\d{2})\/(\d{4})$/.exec(text);
  if (match) {
    const [, day, month, year] = match;
    const date = new Date(Date.UTC(Number(year), Number(month) - 1, Number(day)));
    if (
      date.getUTCFullYear() === Number(year) &&
      date.getUTCMonth() === Number(month) - 1 &&
      date.getUTCDate() === Number(day)
    ) {
      return `${year}-${month}-${day}`;
    }
  }
  throw new ParseError(`invalid date: ${excerpt(text)}`);
}

/** `HH:mm`; the `00:00` placeholder (and an empty cell) mean "not set yet". */
function parseTime(text) {
  if (text === "" || text === "00:00") {
    return null;
  }
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(text)) {
    throw new ParseError(`invalid kickoff time: ${excerpt(text)}`);
  }
  return text;
}

/** `"<home> - <away>"`, split on the first `" - "` only. */
function parseTeams(text) {
  const separator = text.indexOf(" - ");
  if (separator === -1) {
    throw new ParseError('no " - " separator in a game');
  }
  const home = text.slice(0, separator).trim();
  const away = text.slice(separator + 3).trim();
  if (home === "" || away === "") {
    throw new ParseError("empty team name in a game");
  }
  return [home, away];
}

/** The site shows the result away-first: `"a-b"` is away `a`, home `b`. Returns `[home, away]`. */
function parseResult(text) {
  if (text === "") {
    return [null, null];
  }
  const match = /^(\d{1,3})-(\d{1,3})$/.exec(text);
  if (!match) {
    throw new ParseError(`invalid result: ${excerpt(text)}`);
  }
  const [, away, home] = match;
  return [Number(home), Number(away)];
}
