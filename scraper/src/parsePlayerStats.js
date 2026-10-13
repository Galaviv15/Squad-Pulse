// Parses the team's player-statistics table: the GetTeamPlayersStatisticsList response (KAN-70
// findings sections 1, 4–6). One row per registered player, season totals over all competitions.
//
// Privacy: a ParseError message never holds a player's name or player_id. It names a row by its
// 1-based index; only an excerpt of a bad numeric cell is shown.

import { loadHtmlData } from "./asmx.js";
import { queryParameter, readLabelledColumns } from "./parseFixtures.js";
import { ParseError, excerpt } from "./text.js";

export const MAX_PLAYERS = 80;
const JAVA_INT_MAX = 2147483647;

const LABELS = new Map([
  ["שם השחקן", "name"],
  ["מס. משחקים", "games"],
  ["שערים", "goals"],
  ["כ. צהובים ליגה/גביע", "yellowCardsLeagueAndCup"],
  ["כ. צהובים טוטו", "yellowCardsToto"],
  ["כ. אדומים", "redCards"],
  ["הרכב פותח", "starts"],
  ["נכנס כמחליף", "cameOnAsSub"],
  ["הוחלף", "subbedOff"],
  ["דקות משחק", "minutes"],
]);

// The header's data-sort keys, exactly and in this order. "Substitue" is the site's spelling.
const HEADER = new Map([
  ["PlayerName", "name"],
  ["GamesCount", "games"],
  ["Goals", "goals"],
  ["YellowCardsLeague", "yellowCardsLeagueAndCup"],
  ["YellowCardsToto", "yellowCardsToto"],
  ["RedCards", "redCards"],
  ["Opened", "starts"],
  ["Substitue", "cameOnAsSub"],
  ["OutOfGame", "subbedOff"],
  ["TotalMin", "minutes"],
]);

const COUNTS = [
  "games",
  "goals",
  "yellowCardsLeagueAndCup",
  "yellowCardsToto",
  "redCards",
  "starts",
  "cameOnAsSub",
  "subbedOff",
  "minutes",
];

/**
 * → `[{playerId, name, games, goals, yellowCardsLeagueAndCup, yellowCardsToto, redCards, starts,
 * cameOnAsSub, subbedOff, minutes}]` in page order (not meaningful). The name is kept as shown,
 * surname first, never split or reordered. Every row must link a `player_id`, unique in the table,
 * and have `games === starts + cameOnAsSub`; minutes are never checked against games. Ranges are
 * the contract check's job (contract.js).
 */
export function parsePlayerStats(xml) {
  const $ = loadHtmlData(xml, "GetTeamPlayersStatisticsList");
  const header = $("div.table_header_row").first();
  if (header.length === 0) {
    throw new ParseError("no header row in the player statistics table");
  }
  const sortKeys = header
    .children("div.table_col")
    .toArray()
    .map((col) => $(col).attr("data-sort") ?? "");
  if (sortKeys.join(",") !== [...HEADER.keys()].join(",")) {
    throw new ParseError(`unexpected header columns: ${excerpt(sortKeys.join(","))}`);
  }
  const columns = [...HEADER.values()];

  // Every row, not only those linking a player_id: a row without one is an error, not skipped.
  const rows = $("a.table_row").toArray();
  if (rows.length === 0) {
    throw new ParseError("no player rows in the player statistics table");
  }
  if (rows.length > MAX_PLAYERS) {
    throw new ParseError(`more than ${MAX_PLAYERS} player rows in the player statistics table`);
  }
  const seen = new Set();
  return rows.map((row, index) => {
    const at = `row ${index + 1}`;
    const player = parseRow($, row, columns, at);
    if (seen.has(player.playerId)) {
      throw new ParseError(`${at}: duplicate player_id`);
    }
    seen.add(player.playerId);
    return player;
  });
}

function parseRow($, row, columns, at) {
  let cells;
  try {
    cells = readLabelledColumns($, row, LABELS);
  } catch (error) {
    if (error instanceof ParseError) throw new ParseError(`${at}: ${error.message}`);
    throw error;
  }
  const order = [...cells.keys()];
  if (order.some((field, i) => field !== columns[i])) {
    throw new ParseError(`${at}: columns not in the header's order`);
  }

  const rawId = queryParameter($(row).attr("href") ?? "", "player_id");
  if (rawId === null) {
    throw new ParseError(`${at}: no player_id`);
  }
  if (!/^\d{1,10}$/.test(rawId) || Number(rawId) < 1 || Number(rawId) > JAVA_INT_MAX) {
    throw new ParseError(`${at}: invalid player_id`);
  }
  const name = cells.get("name").text;
  if (name === "") {
    throw new ParseError(`${at}: empty name`);
  }

  const player = { playerId: Number(rawId), name };
  for (const key of COUNTS) {
    const text = cells.get(key).text;
    if (!/^\d{1,5}$/.test(text)) {
      throw new ParseError(`${at}: invalid ${key}: ${excerpt(text)}`);
    }
    player[key] = Number(text);
  }
  if (player.games !== player.starts + player.cameOnAsSub) {
    throw new ParseError(`${at}: games must equal starts + cameOnAsSub`);
  }
  return player;
}
