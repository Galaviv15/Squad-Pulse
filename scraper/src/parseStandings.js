// Parses the league page (round, box, title) and the LeagueTable response (findings sections 1, 4, 6, 9).

import { load } from "cheerio";
import { cleanText, queryParameter, readLabelledColumns } from "./parseFixtures.js";
import { ParseError, excerpt } from "./text.js";

export const NO_ROUND_DETAIL = "no standings round on the league page";

const LABELS = new Map([
  ["מיקום", "position"],
  ["קבוצה", "team"],
  ["משחקים", "played"],
  ["ניצחונות", "won"],
  ["תיקו", "drawn"],
  ["הפסדים", "lost"],
  ["שערים", "goals"],
  ["נקודות", "points"],
]);

// Cross-check: the columns that carry a semantic class must carry it.
const EXPECTED_CLASS = { position: "place", team: "team_name", goals: "goals-col" };

/** Reads the standings placeholder. Missing / non-numeric / < 1 round or box → season-start error. */
export function parseLeaguePage(html) {
  const $ = load(html);
  const section = $("section.league-standing-table").first();
  const round = positiveInteger(section.attr("data-table-round"));
  const box = positiveInteger(section.attr("data-table-index"));
  if (round === null || box === null) {
    throw new ParseError(NO_ROUND_DETAIL);
  }
  const title = cleanText(section.attr("data-table-title") ?? "");
  if (title.length > 100) {
    throw new ParseError("standings title too long on the league page");
  }
  return { round, box, title };
}

function positiveInteger(raw) {
  const text = (raw ?? "").trim();
  if (!/^\d{1,9}$/.test(text) || Number(text) < 1) {
    return null;
  }
  return Number(text);
}

/** Parses the ASMX envelope, then the unescaped HTML in `HtmlData`. Goals are shown against-for. */
export function parseLeagueTable(xml) {
  const envelope = load(xml, { xml: true });
  const htmlData = envelope("HtmlData");
  if (htmlData.length !== 1) {
    throw new ParseError("no HtmlData in the LeagueTable response");
  }
  const $ = load(htmlData.text());
  const rows = $('a.table_row[href*="team_id="]').toArray();
  if (rows.length === 0) {
    throw new ParseError("no standings rows in the LeagueTable response");
  }
  return rows.map((row) => parseStandingsRow($, row));
}

function parseStandingsRow($, row) {
  const cells = readLabelledColumns($, row, LABELS);
  for (const [key, className] of Object.entries(EXPECTED_CLASS)) {
    if (!cells.get(key).element.hasClass(className)) {
      throw new ParseError(`column class mismatch: ${key}`);
    }
  }
  const teamId = queryParameter($(row).attr("href"), "team_id");
  if (!/^\d{1,10}$/.test(teamId ?? "") || Number(teamId) < 1) {
    throw new ParseError(`invalid team_id: ${excerpt(teamId)}`);
  }
  const goals = /^(\d{1,4})-(\d{1,4})$/.exec(cells.get("goals").text);
  if (!goals) {
    throw new ParseError(`invalid goals: ${excerpt(cells.get("goals").text)}`);
  }
  return {
    position: integer(cells, "position"),
    teamId: Number(teamId),
    teamName: cells.get("team").text,
    played: integer(cells, "played"),
    won: integer(cells, "won"),
    drawn: integer(cells, "drawn"),
    lost: integer(cells, "lost"),
    goalsFor: Number(goals[2]),
    goalsAgainst: Number(goals[1]),
    points: integer(cells, "points", true),
  };
}

function integer(cells, key, signed = false) {
  const text = cells.get(key).text;
  if (!(signed ? /^-?\d{1,4}$/ : /^\d{1,4}$/).test(text)) {
    throw new ParseError(`invalid ${key}: ${excerpt(text)}`);
  }
  return Number(text);
}
