import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { parseGamesPage } from "../src/parseFixtures.js";
import { NO_ROUND_DETAIL, parseLeaguePage, parseLeagueTable } from "../src/parseStandings.js";
import { ParseError, normalizeText } from "../src/text.js";
import { GAMES_HTML, TABLE_XML, leaguePage } from "./helpers.js";

const SDEROT = "מ.כ. שדרות";

describe("games page", () => {
  const games = parseGamesPage(GAMES_HTML);

  test("34 games: 6 with a result, 28 unscheduled", () => {
    assert.equal(games.length, 34);
    assert.equal(games.filter((g) => g.homeScore !== null).length, 6);
    const unscheduled = games.filter((g) => g.gameId === null);
    assert.equal(unscheduled.length, 28);
    for (const game of unscheduled) {
      assert.equal(game.kickoffTime, null);
      assert.equal(game.venue, null);
      assert.equal(game.homeScore, null);
      assert.equal(game.awayScore, null);
      assert.match(game.date, /^2026-|^2027-/);
    }
  });

  test("away-first result: 'צעירי טירה - שדרות' shown 4-1 is home 1, away 4", () => {
    const game = games.find((g) => g.date === "2026-09-03");
    assert.deepEqual(game, {
      gameId: 1122631,
      date: "2026-09-03",
      kickoffTime: "21:15",
      homeTeamName: "מ.כ. צעירי טירה",
      awayTeamName: SDEROT,
      venue: "חדרה מתחם אימונים סינטטי",
      homeScore: 1,
      awayScore: 4,
    });
  });

  test("away-first result: Sderot at home, shown 2-1, is home 1, away 2", () => {
    const game = games.find((g) => g.date === "2026-09-09");
    assert.equal(game.homeTeamName, SDEROT);
    assert.equal(game.awayTeamName, "מכבי קרית מלאכי עירוני");
    assert.equal(game.homeScore, 1);
    assert.equal(game.awayScore, 2);
  });

  test("punctuation in names survives; repeated spaces are collapsed", () => {
    const game = games.find((g) => g.date === "2026-09-17");
    assert.equal(game.homeTeamName, "הפ' מחנה יהודה מאיה,אלירן");
    assert.equal(game.awayTeamName, SDEROT);
  });

  test("names with U+00A0 and repeated whitespace are normalized", () => {
    const html = GAMES_HTML.replace(
      "מ.כ. צעירי טירה - מ.כ. שדרות",
      "מ.כ.\u00a0צעירי   טירה\u00a0- \n מ.כ. שדרות",
    );
    const game = parseGamesPage(html).find((g) => g.date === "2026-09-03");
    assert.equal(game.homeTeamName, "מ.כ. צעירי טירה");
    assert.equal(game.awayTeamName, SDEROT);
    assert.equal(normalizeText("\u00a0 a\u00a0\u00a0b \t"), "a b");
  });

  test("keeps page order", () => {
    const dates = games.map((g) => g.date);
    assert.equal(dates[0], "2026-09-03");
    assert.equal(dates[1], "2026-09-09");
  });
});

describe("games page: malformed input is a ParseError", () => {
  const row = (cols, href = "/leagues/games/game/?game_id=5") =>
    `<section class="games_table"><a class="table_row" href="${href}">${cols}</a></section>`;
  const col = (label, value) =>
    `<div class="table_col"><span class="sr-only">${label}</span>${value}</div>`;
  const cols = (over = {}) => {
    const values = {
      תאריך: "03/09/2026",
      משחק: "א - ב",
      אצטדיון: "",
      שעה: "20:00",
      תוצאה: "",
      ...over,
    };
    return Object.entries(values)
      .map(([label, value]) => col(label, value))
      .join("");
  };
  const cases = {
    "a missing label": row(cols() + '<div class="table_col">x</div>'),
    "a missing column": row(cols().replace(col("שעה", "20:00"), "")),
    "an unknown label": row(cols() + col("קהל", "100")),
    'a score "x"': row(cols({ תוצאה: "x" })),
    "date 31/02/2026": row(cols({ תאריך: "31/02/2026" })),
    "game_id=abc": row(cols(), "/leagues/games/game/?game_id=abc"),
    "no rows": "<section class='games_table'></section>",
    'no " - " separator': row(cols({ משחק: "א-ב" })),
    "a control character": row(cols({ אצטדיון: "x\u0001y" })),
  };
  for (const [name, html] of Object.entries(cases)) {
    test(name, () => assert.throws(() => parseGamesPage(html), ParseError));
  }

  test("the synthetic row itself parses (so each case fails for its own reason)", () => {
    assert.equal(parseGamesPage(row(cols())).length, 1);
  });
});

describe("standings", () => {
  const rows = parseLeagueTable(TABLE_XML);

  test("18 rows, team id from every row", () => {
    assert.equal(rows.length, 18);
    for (const row of rows) assert.ok(Number.isInteger(row.teamId) && row.teamId > 0);
    assert.deepEqual(
      rows.map((r) => r.position),
      Array.from({ length: 18 }, (_, i) => i + 1),
    );
  });

  test("Sderot 7617: P6 W2 D1 L3, goals 7 for / 8 against, 7 points", () => {
    const row = rows.find((r) => r.teamId === 7617);
    assert.equal(row.teamName, SDEROT);
    assert.deepEqual(
      [row.played, row.won, row.drawn, row.lost, row.goalsFor, row.goalsAgainst, row.points],
      [6, 2, 1, 3, 7, 8, 7],
    );
  });

  test("against-for goals: Dimona shown 8-12 is for 12, against 8", () => {
    const dimona = rows.find((r) => r.teamName === "מ.ס. דימונה");
    assert.equal(dimona.goalsFor, 12);
    assert.equal(dimona.goalsAgainst, 8);
  });

  test("names with quotes, dots and commas survive intact", () => {
    const names = rows.map((r) => r.teamName);
    assert.ok(names.some((n) => n.includes('"')));
    assert.ok(names.includes("הפ' מחנה יהודה מאיה,אלירן"));
  });

  test("cross-check: Sderot's six parsed results equal its standings row", () => {
    const games = parseGamesPage(GAMES_HTML).filter((g) => g.homeScore !== null);
    assert.equal(games.length, 6);
    let won = 0,
      drawn = 0,
      lost = 0,
      goalsFor = 0,
      goalsAgainst = 0;
    for (const g of games) {
      const home = g.homeTeamName === SDEROT;
      const ours = home ? g.homeScore : g.awayScore;
      const theirs = home ? g.awayScore : g.homeScore;
      goalsFor += ours;
      goalsAgainst += theirs;
      if (ours > theirs) won += 1;
      else if (ours === theirs) drawn += 1;
      else lost += 1;
    }
    const row = rows.find((r) => r.teamId === 7617);
    assert.deepEqual(
      [won, drawn, lost, goalsFor, goalsAgainst],
      [row.won, row.drawn, row.lost, row.goalsFor, row.goalsAgainst],
    );
  });

  test("every opponent name in the fixtures is a standings name, exactly", () => {
    const names = new Set(rows.map((r) => r.teamName));
    for (const g of parseGamesPage(GAMES_HTML)) {
      assert.ok(names.has(g.homeTeamName), g.homeTeamName);
      assert.ok(names.has(g.awayTeamName), g.awayTeamName);
    }
  });

  test("no HtmlData / no rows → ParseError", () => {
    assert.throws(() => parseLeagueTable("<ResponseData/>"), ParseError);
    assert.throws(
      () => parseLeagueTable("<ResponseData><HtmlData>&lt;div/&gt;</HtmlData></ResponseData>"),
      ParseError,
    );
  });

  test("a goals cell without the goals-col class fails the cross-check", () => {
    assert.throws(
      () => parseLeagueTable(TABLE_XML.replaceAll("goals-col", "x")),
      /column class mismatch/,
    );
  });
});

describe("league page: round and box", () => {
  test("extracted from the placeholder", () => {
    assert.deepEqual(parseLeaguePage(leaguePage()), { round: 6, box: 20, title: "טבלת ליגה" });
  });

  const seasonStart = {
    "missing round": leaguePage({ round: null }),
    "round 0": leaguePage({ round: "0" }),
    "non-numeric round": leaguePage({ round: "x" }),
    "missing box": leaguePage({ box: null }),
    "box 0": leaguePage({ box: "0" }),
    "no section": "<html><body></body></html>",
  };
  for (const [name, html] of Object.entries(seasonStart)) {
    test(`${name} → the season-start PARSE_ERROR`, () => {
      assert.throws(() => parseLeaguePage(html), { name: "Error", message: NO_ROUND_DETAIL });
    });
  }
});
