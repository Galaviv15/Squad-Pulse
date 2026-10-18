// The State Cup list and the team page (KAN-85), against the captured fixtures and variants of them.

import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { load } from "cheerio";
import { parseCupGames, parseOwnName, readCupEnvelope } from "../src/parseCupGames.js";
import { ParseError } from "../src/text.js";
import { fixture } from "./helpers.js";

const SDEROT = "מועדון כדורגל שדרות";
const RAMAT_HASHARON = "הפועל ניר רמת השרון";
const MUSMUS = "הפועל בני מוסמוס";
const NATCUP_7617 = fixture("natcup-7617-s28.xml");
const NATCUP_2194 = fixture("natcup-2194-s28.xml");
const NATCUP_2338 = fixture("natcup-2338-s28.xml");
const TEAM_7617 = fixture("team-7617-s28.html");

/** The `Games` HTML of an envelope, as the site's JSON carries it. */
const gamesOf = (xml) => JSON.parse(load(xml, { xml: true })("string").text()).Games;

/** An envelope as the site writes it: JSON with `<`, `>`, `'` and `&` as `\u` escapes. */
function envelope(object) {
  const json = JSON.stringify(object).replace(
    /[<>'&]/g,
    (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, "0")}`,
  );
  return `<?xml version="1.0" encoding="utf-8"?>\r\n<string xmlns="http://tempuri.org/">${json}</string>`;
}

/** The Sderot list with its Games HTML changed by `change` (which must change something). */
function variant(change, extra = {}) {
  const games = gamesOf(NATCUP_7617);
  const changed = change(games);
  assert.notEqual(changed, games, "the variant changed nothing");
  return envelope({ StandingTable: null, Comments: null, Games: changed, ...extra });
}

const parse = (xml, ownName = SDEROT) => parseCupGames(xml, ownName);
const rejects = (xml, message, ownName = SDEROT) =>
  assert.throws(
    () => parse(xml, ownName),
    (error) => {
      assert.ok(error instanceof ParseError);
      assert.equal(error.message, message);
      return true;
    },
  );

describe("the envelope and its text (Cheerio, installed version)", () => {
  test("the XML text keeps the JSON escapes; JSON.parse turns them into the HTML", () => {
    const text = load(NATCUP_7617, { xml: true })("string").text();
    assert.ok(text.includes("\\u003c") && text.includes("\\u0026nbsp;"));
    assert.ok(!text.includes("<"));
    const games = JSON.parse(text).Games;
    assert.ok(games.startsWith("<h2 class='white_title'>"));
    assert.ok(games.includes("&nbsp;- </span>"));
  });

  test("load(Games) turns &nbsp; into U+00A0", () => {
    const first = load(gamesOf(NATCUP_7617))("span.team-name-text").first().text();
    assert.equal(first, `${SDEROT}\u00a0- `);
  });

  test("a synthetic envelope reads like the real one", () => {
    const real = readCupEnvelope(NATCUP_7617, "X");
    const json = JSON.parse(load(NATCUP_7617, { xml: true })("string").text());
    assert.deepEqual(readCupEnvelope(envelope(json), "X"), real);
  });
});

describe("readCupEnvelope", () => {
  test("Games null (the Toto fixture's envelope) → an empty OK list", () => {
    assert.deepEqual(readCupEnvelope(fixture("totocup-7617-s28.xml"), "X"), {
      games: null,
      extras: [],
    });
    assert.deepEqual(parse(fixture("totocup-7617-s28.xml")), { games: [], extras: [] });
  });

  test("a non-null StandingTable or Comments is reported, never a failure", () => {
    assert.deepEqual(parse(NATCUP_7617).extras, []);
    const withTable = envelope({ StandingTable: "<table></table>", Comments: "x", Games: null });
    assert.deepEqual(parse(withTable), { games: [], extras: ["StandingTable", "Comments"] });
    const games = gamesOf(NATCUP_7617);
    const both = envelope({ StandingTable: { rows: [] }, Comments: null, Games: games });
    assert.equal(parse(both).games.length, 2);
    assert.deepEqual(parse(both).extras, ["StandingTable"]);
  });

  const broken = {
    "no string root": [
      '<?xml version="1.0"?><ResponseData><HtmlData>x</HtmlData></ResponseData>',
      "no string root element in the GetTeamNatCupData response",
    ],
    "two root elements": [
      "<string>{}</string><string>{}</string>",
      "no string root element in the GetTeamNatCupData response",
    ],
    "an HTML error page": [
      "<html><body>error</body></html>",
      "no string root element in the GetTeamNatCupData response",
    ],
    "invalid JSON": [
      '<string xmlns="http://tempuri.org/">{"Games":</string>',
      "the GetTeamNatCupData response isn't JSON",
    ],
    "no Games key": [
      envelope({ StandingTable: null }),
      "no Games in the GetTeamNatCupData response",
    ],
    "a JSON array": [envelope([1]), "no Games in the GetTeamNatCupData response"],
    "a JSON null": [envelope(null), "no Games in the GetTeamNatCupData response"],
    "Games a number": [
      envelope({ Games: 3 }),
      "Games isn't a string in the GetTeamNatCupData response",
    ],
  };
  for (const [name, [xml, message]] of Object.entries(broken)) {
    test(`${name} → ParseError naming the endpoint, no value`, () => rejects(xml, message));
  }
});

describe("parseOwnName (the team page)", () => {
  test("the fixture's h1 span.big", () => {
    assert.equal(parseOwnName(TEAM_7617), SDEROT);
  });

  test("trimmed, U+00A0 and runs of spaces normalized", () => {
    const html = TEAM_7617.replace(`>${SDEROT}<`, `>  ${SDEROT.replace(" ", "\u00a0")} <`);
    assert.equal(parseOwnName(html), SDEROT);
  });

  for (const [name, change, message] of [
    [
      "zero",
      (h) => h.replace("class='big'", "class='small'"),
      "expected one h1.page_main_title span.big",
    ],
    [
      "two",
      (h) => h.replace("</h1>", "<span class='big'>אחר</span></h1>"),
      "expected one h1.page_main_title span.big",
    ],
    [
      "empty",
      (h) => h.replace(`>${SDEROT}<`, "> \u00a0 <"),
      "an empty club name in the team page h1",
    ],
    [
      "outside the h1",
      (h) => h.replace('<h1 class="page_main_title container clearfix">', "<h1>"),
      "expected one h1.page_main_title span.big",
    ],
  ]) {
    test(`${name} span.big → ParseError`, () => {
      const html = change(TEAM_7617);
      assert.notEqual(html, TEAM_7617);
      assert.throws(() => parseOwnName(html), { name: "Error", message });
    });
  }
});

describe("parseCupGames: the captured lists", () => {
  test("Sderot: 2 played games, scores home first, our side by the full name", () => {
    const { games, extras } = parse(NATCUP_7617);
    assert.deepEqual(extras, []);
    assert.deepEqual(games, [
      {
        gameId: 1123491,
        round: "ה' - ליגות א' דרום",
        date: "2026-08-27",
        kickoffTime: "19:00",
        homeTeamName: SDEROT,
        awayTeamName: "שמשון פזטל תל אביב",
        ownSide: "HOME",
        venue: "שדרות אצטדיון שדרות",
        homeScore: 1,
        awayScore: 0,
        extraTimeHomeScore: null,
        extraTimeAwayScore: null,
        penaltiesHomeScore: null,
        penaltiesAwayScore: null,
      },
      {
        gameId: 1142007,
        round: "ו",
        date: "2026-10-12",
        kickoffTime: "15:00",
        homeTeamName: "הפועל רהט",
        awayTeamName: SDEROT,
        ownSide: "AWAY",
        venue: "רהט הפועל רהט",
        homeScore: 1,
        awayScore: 4,
        extraTimeHomeScore: null,
        extraTimeAwayScore: null,
        penaltiesHomeScore: null,
        penaltiesAwayScore: null,
      },
    ]);
  });

  test("extra time (natcup-2194): the away-first cell swapped like the result", () => {
    const { games } = parse(NATCUP_2194, RAMAT_HASHARON);
    const scores = games.map((g) => [
      g.gameId,
      g.ownSide,
      g.homeScore,
      g.awayScore,
      g.extraTimeHomeScore,
      g.extraTimeAwayScore,
      g.penaltiesHomeScore,
    ]);
    assert.deepEqual(scores, [
      // List 0-0, extra time 1-2 (away-first) → home 2, away 1.
      [1123457, "HOME", 0, 0, 2, 1, null],
      // List 2-2, extra time 2-3; the game page shows הארכה : 3 : 2, home first.
      [1123493, "HOME", 2, 2, 3, 2, null],
      [1142009, "HOME", 2, 0, null, null, null],
    ]);
    assert.equal(games[1].round, "ה' - ליגות א' דרום");
  });

  test("penalties (natcup-2338): the away-first cell swapped; the game page shows 3 : 1 home first", () => {
    const [game] = parse(NATCUP_2338, MUSMUS).games;
    assert.deepEqual(
      [game.gameId, game.ownSide, game.homeScore, game.awayScore],
      [1123518, "HOME", 3, 3],
    );
    assert.deepEqual([game.extraTimeHomeScore, game.extraTimeAwayScore], [3, 3]);
    assert.deepEqual([game.penaltiesHomeScore, game.penaltiesAwayScore], [3, 1]);
    assert.equal(game.venue, "חדרה מתחם אימונים סינטטי");
  });

  test("a cell's sr-only label is never read as a value (both forms of each score cell)", () => {
    // Empty form: the cell holds only its label. Filled form: label + value.
    const $ = load(gamesOf(NATCUP_2338));
    const cells = $("a.table_row").first().children("div.table_col");
    const without = (i) => {
      const copy = $(cells[i]).clone();
      copy.find(".sr-only").remove();
      return copy.text();
    };
    assert.deepEqual([5, 6, 7].map(without), ["3-3", "3-3", "1-3"]);
    assert.deepEqual(
      [5, 6, 7].map((i) => $(cells[i]).find(".sr-only").text()),
      ["תוצאה", "הארכה", "ב.הכרעה"],
    );
    const empty = load(gamesOf(NATCUP_7617))("a.table_row").first().children("div.table_col");
    assert.equal($(empty[6]).text(), "הארכה");
    const copy = $(empty[6]).clone();
    copy.find(".sr-only").remove();
    assert.equal(copy.text(), "");
  });
});

describe("parseCupGames: variants", () => {
  test("a name with a hyphen is kept whole (never split on '-')", () => {
    const named = "הפועל באר-שבע - דרום";
    const xml = variant((g) => g.replaceAll("שמשון פזטל תל אביב", named));
    assert.equal(parse(xml).games[0].awayTeamName, named);
    const asOurs = variant((g) => g.replaceAll(SDEROT, named));
    assert.deepEqual(
      parse(asOurs, named).games.map((x) => [x.homeTeamName, x.ownSide]),
      [
        [named, "HOME"],
        ["הפועל רהט", "AWAY"],
      ],
    );
  });

  test("neither or both teams our name → ParseError (never a guess)", () => {
    rejects(NATCUP_7617, "neither cup row teams are our team", "מ.כ. שדרות");
    const both = variant((g) => g.replace("שמשון פזטל תל אביב", SDEROT));
    rejects(both, "both cup row teams are our team");
  });

  test("ownName is matched exactly, not by a part of the name", () => {
    rejects(NATCUP_7617, "neither cup row teams are our team", "שדרות");
  });

  test("a row with 7 cells → ParseError", () => {
    const xml = variant((g) =>
      g.replace(/<div class='table_col '><span class='sr-only'>ב\.הכרעה<\/span><\/div>/, ""),
    );
    rejects(xml, "a cup row without 8 cells");
  });

  test("a score cell that isn't a score → ParseError, for each of the three", () => {
    for (const [label, what] of [
      ["תוצאה", "result"],
      ["הארכה", "extra time"],
      ["ב.הכרעה", "penalties"],
    ]) {
      const xml = variant((g) =>
        g.replace(
          new RegExp(`(<span class='sr-only'>${label.replace(".", "\\.")}</span>)[^<]*`),
          "$1ב.מ",
        ),
      );
      rejects(xml, `a cup ${what} cell that isn't a score`);
    }
    rejects(
      variant((g) =>
        g.replace(
          "<span class='sr-only'>תוצאה</span>0-1",
          "<span class='sr-only'>תוצאה</span>100-1",
        ),
      ),
      "a cup result cell that isn't a score",
    );
  });

  test("a game_id of -1, or no link → gameId null", () => {
    const minusOne = variant((g) => g.replace("game_id=1123491", "game_id=-1"));
    assert.equal(parse(minusOne).games[0].gameId, null);
    const noLink = variant((g) => g.replace("href='/leagues/games/game/?game_id=1123491'", ""));
    assert.deepEqual(
      parse(noLink).games.map((x) => x.gameId),
      [null, 1142007],
    );
    const bad = variant((g) => g.replace("game_id=1123491", "game_id=abc"));
    rejects(bad, "an invalid game_id in a cup row");
  });

  test("a future row: empty result → both scores null; 00:00 → kickoffTime null", () => {
    const xml = variant((g) =>
      g
        .replace("<span class='sr-only'>תוצאה</span>0-1", "<span class='sr-only'>תוצאה</span>")
        .replace("<span class='sr-only'>שעה</span>19:00", "<span class='sr-only'>שעה</span>00:00"),
    );
    const [game] = parse(xml).games;
    assert.deepEqual([game.homeScore, game.awayScore, game.kickoffTime], [null, null, null]);
  });

  test("an empty venue → null", () => {
    const xml = variant((g) =>
      g.replace(
        "<span class='sr-only'>אצטדיון</span>שדרות אצטדיון שדרות",
        "<span class='sr-only'>אצטדיון</span>",
      ),
    );
    assert.equal(parse(xml).games[0].venue, null);
  });

  test("a list with the header row only → no games", () => {
    const xml = variant((g) => g.replace(/<a class='table_row[\s\S]*?<\/a>/g, ""));
    assert.deepEqual(parse(xml).games, []);
  });

  test("an invalid date, an empty round, one team name → fixed messages, no page text", () => {
    rejects(
      variant((g) => g.replace("27/08/2026", "31/02/2026")),
      "an invalid date in a cup row",
    );
    rejects(
      variant((g) =>
        g.replace("<span class='sr-only'>תאריך</span>ו<", "<span class='sr-only'>תאריך</span><"),
      ),
      "a cup round outside 1..100",
    );
    rejects(
      variant((g) => g.replace("<span class='team-name-text'>שמשון פזטל תל אביב</span>", "")),
      "a cup row without two team names",
    );
    rejects(
      variant((g) =>
        g.replace("<span class='sr-only'>שעה</span>19:00", "<span class='sr-only'>שעה</span>7pm"),
      ),
      "an invalid kickoff time in a cup row",
    );
  });

  test("Games without the table → ParseError", () => {
    rejects(
      envelope({ StandingTable: null, Comments: null, Games: "<h2>רשימת המשחקים</h2>" }),
      "expected one div.table_view in the cup list",
    );
  });

  test("duplicate rows are passed on, not rejected (the backend's DUPLICATE_GAME)", () => {
    const xml = variant((g) => g.replace(/(<a class='table_row[\s\S]*?<\/a>)/, "$1$1"));
    assert.deepEqual(
      parse(xml).games.map((x) => x.gameId),
      [1123491, 1123491, 1142007],
    );
  });
});
