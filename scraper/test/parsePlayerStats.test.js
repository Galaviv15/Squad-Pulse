import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { parsePlayerStats } from "../src/parsePlayerStats.js";
import { ParseError } from "../src/text.js";
import { STATS_XML } from "./helpers.js";

const players = parsePlayerStats(STATS_XML);
const byId = (id) => players.find((p) => p.playerId === id);

// Every name and player_id in the fixture: no ParseError message may contain one.
const NAMES = players.map((p) => p.name);
const IDS = players.map((p) => String(p.playerId));

const FIELDS = [
  "playerId",
  "name",
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

describe("player statistics table", () => {
  test("34 rows, 34 distinct playerIds, in page order", () => {
    assert.equal(players.length, 34);
    assert.equal(new Set(IDS).size, 34);
    assert.deepEqual(
      players.slice(0, 3).map((p) => p.playerId),
      [48702, 158410, 136249],
    );
  });

  test("every row has exactly the contract's fields", () => {
    for (const player of players) assert.deepEqual(Object.keys(player), FIELDS);
  });

  test("the 7 players with 0 games and 0 minutes are kept", () => {
    const zero = players.filter((p) => p.games === 0 && p.minutes === 0).map((p) => p.playerId);
    assert.deepEqual(
      zero.sort((a, b) => a - b),
      [105826, 181014, 203484, 206327, 222522, 231487, 246425],
    );
  });

  test("names are kept as shown, surname first, never reordered", () => {
    assert.equal(byId(85286).name, "אביב גל");
    assert.equal(byId(48702).name, "אבוחצירה ירדן משה");
  });

  test("games = starts + cameOnAsSub on every row", () => {
    for (const p of players) assert.equal(p.games, p.starts + p.cameOnAsSub, String(p.playerId));
  });

  test("minutes above 90 × games are accepted", () => {
    assert.deepEqual([byId(158410).games, byId(158410).minutes], [1, 96]);
    assert.deepEqual([byId(120427).games, byId(120427).minutes], [8, 769]);
  });

  test("one full row", () => {
    assert.deepEqual(byId(48702), {
      playerId: 48702,
      name: "אבוחצירה ירדן משה",
      games: 8,
      goals: 0,
      yellowCardsLeagueAndCup: 4,
      yellowCardsToto: 0,
      redCards: 0,
      starts: 7,
      cameOnAsSub: 1,
      subbedOff: 4,
      minutes: 629,
    });
  });

  test("80 rows (the contract's maximum) parse", () => {
    const first = /&lt;a class='table_row[\s\S]*?&lt;\/a&gt;/.exec(STATS_XML)[0];
    const extra = Array.from({ length: 46 }, (_, i) =>
      first.replace("player_id=48702&", `player_id=${i + 1}&`),
    ).join("");
    const xml = STATS_XML.replace(first, first + extra);
    assert.equal(parsePlayerStats(xml).length, 80);
  });
});

describe("player statistics table: malformed input is a ParseError without names or ids", () => {
  const ROW = /&lt;a class='table_row[\s\S]*?&lt;\/a&gt;/;
  const ALL_ROWS = new RegExp(ROW.source, "g");
  const firstRow = ROW.exec(STATS_XML)[0];
  const GOALS_CELL =
    /&lt;div class='table_col'&gt;&lt;span class='sr-only'&gt;שערים&lt;\/span&gt; \d+&lt;\/div&gt;/;

  const cases = {
    "an unknown label": [
      STATS_XML.replace("sr-only'&gt;שערים&lt;", "sr-only'&gt;שערים נוספים&lt;"),
      /^row 1: unknown column label/,
    ],
    "a missing column": [STATS_XML.replace(GOALS_CELL, ""), /^row 1: missing column: שערים$/],
    "a row without player_id": [
      STATS_XML.replace("?player_id=48702&", "?x=48702&"),
      /^row 1: no player_id$/,
    ],
    "a non-numeric player_id": [
      STATS_XML.replace("player_id=48702&", "player_id=48a02&"),
      /^row 1: invalid player_id$/,
    ],
    "player_id 0": [
      STATS_XML.replace("player_id=48702&", "player_id=0&"),
      /^row 1: invalid player_id$/,
    ],
    "player_id over Java's int": [
      STATS_XML.replace("player_id=48702&", "player_id=2147483648&"),
      /^row 1: invalid player_id$/,
    ],
    "a duplicate player_id": [
      STATS_XML.replace("player_id=158410&", "player_id=48702&"),
      /^row 2: duplicate player_id$/,
    ],
    "a non-numeric count": [
      STATS_XML.replace("מס. משחקים&lt;/span&gt; 8&lt;", "מס. משחקים&lt;/span&gt; 8x&lt;"),
      /^row 1: invalid games: 8x$/,
    ],
    "a negative count": [
      STATS_XML.replace("מס. משחקים&lt;/span&gt; 8&lt;", "מס. משחקים&lt;/span&gt; -8&lt;"),
      /^row 1: invalid games: -8$/,
    ],
    "games ≠ starts + cameOnAsSub": [
      STATS_XML.replace("מס. משחקים&lt;/span&gt; 8&lt;", "מס. משחקים&lt;/span&gt; 9&lt;"),
      /^row 1: games must equal starts \+ cameOnAsSub$/,
    ],
    "an empty name": [
      STATS_XML.replace(" אבוחצירה ירדן משה&lt;/div&gt;", "&lt;/div&gt;"),
      /^row 1: empty name$/,
    ],
    "0 rows": [STATS_XML.replace(ALL_ROWS, ""), /^no player rows/],
    "81 rows": [STATS_XML.replace(firstRow, firstRow.repeat(48)), /^more than 80 player rows/],
    "no HtmlData": [
      "<ResponseData/>",
      /^no HtmlData in the GetTeamPlayersStatisticsList response$/,
    ],
    "a missing header row": [
      STATS_XML.replace("table_header_row", "table_other_row"),
      /^no header row/,
    ],
    'the header\'s "Substitue" fixed to "Substitute"': [
      STATS_XML.replace("data-sort='Substitue'", "data-sort='Substitute'"),
      /^unexpected header columns/,
    ],
    "two header columns swapped": [
      STATS_XML.replace("data-sort='Goals'", "data-sort='X'")
        .replace("data-sort='GamesCount'", "data-sort='Goals'")
        .replace("data-sort='X'", "data-sort='GamesCount'"),
      /^unexpected header columns/,
    ],
    "a row's cells in another order than the header": [
      STATS_XML.replace(
        firstRow,
        firstRow
          .replace(GOALS_CELL, "")
          .replace("&lt;/a&gt;", GOALS_CELL.exec(firstRow)[0] + "&lt;/a&gt;"),
      ),
      /^row 1: columns not in the header's order$/,
    ],
  };
  for (const [name, [xml, message]] of Object.entries(cases)) {
    test(name, () => {
      assert.notEqual(xml, STATS_XML, "the mutation must change the fixture");
      let error;
      assert.throws(
        () => parsePlayerStats(xml),
        (e) => {
          error = e;
          return e instanceof ParseError;
        },
      );
      assert.match(error.message, message);
      for (const value of [...NAMES, ...IDS]) {
        assert.ok(!error.message.includes(value), `${name}: the message leaks a name or id`);
      }
    });
  }
});
