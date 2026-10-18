import assert from "node:assert/strict";
import { describe, test } from "node:test";
import { parseCupGames } from "../src/parseCupGames.js";
import { parseGameDetails } from "../src/parseGameDetails.js";
import { parseGamesPage } from "../src/parseFixtures.js";
import { ParseError } from "../src/text.js";
import { GAMES_HTML, fixture } from "./helpers.js";

const ROWS = parseGamesPage(GAMES_HTML);
const rowOf = (gameId) => ROWS.find((row) => row.gameId === gameId);
// The cup game isn't on the team-games page (KAN-76): a hand-built row.
const CUP_ROW = {
  gameId: 1142007,
  date: "2026-10-12",
  kickoffTime: "15:00",
  homeTeamName: "הפועל רהט",
  awayTeamName: "מ.כ. שדרות",
  venue: null,
  homeScore: 1,
  awayScore: 4,
};
const ROW = {
  1122670: rowOf(1122670),
  1143937: rowOf(1143937),
  1122634: rowOf(1122634),
  1142007: CUP_ROW,
};
const HTML = Object.fromEntries(Object.keys(ROW).map((id) => [id, fixture(`game-${id}.html`)]));

const parse = (gameId, html = HTML[gameId], row = ROW[gameId]) =>
  parseGameDetails(html, { gameId, teamId: 7617, row, kind: "LEAGUE" });
const gameOf = (gameId, html, row) => parse(gameId, html, row).game;

/** Replaces `from` with `to` once, and fails if `from` isn't there (a stale variant). */
function swap(html, from, to) {
  assert.ok(html.includes(from), `variant source not found: ${from}`);
  return html.replace(from, to);
}

/** Applies `change` to one player's link block (`<a … player_id=ID…>…</a>`) only. */
function inPlayer(html, playerId, change) {
  const start = html.indexOf(`player_id=${playerId}`);
  assert.ok(start >= 0, `player ${playerId} not in the page`);
  const end = html.indexOf("</a>", start);
  return html.slice(0, start) + change(html.slice(start, end)) + html.slice(end);
}

/** Removes one player's whole `<a>` block. */
function withoutPlayer(html, playerId) {
  const at = html.indexOf(`player_id=${playerId}`);
  const start = html.lastIndexOf("<a ", at);
  const end = html.indexOf("</a>", at) + "</a>".length;
  return html.slice(0, start) + html.slice(end);
}

function assertParseError(run, pattern) {
  assert.throws(run, (error) => {
    assert.ok(error instanceof ParseError, `${error}`);
    assert.match(error.message, pattern);
    return true;
  });
}

const roles = (game) =>
  game.players.reduce((counts, p) => ({ ...counts, [p.role]: (counts[p.role] ?? 0) + 1 }), {});
const playerOf = (game, playerId) => game.players.find((p) => p.playerId === playerId);
// The anonymised opponent (fixtures README): never in any output.
const OPPONENT_NAME = "שחקן יריב";
const OPPONENT_ID = /9000\d\d/;

const GAME_1122670 = {
  gameId: 1122670,
  date: "2026-10-09",
  kickoffTime: "13:35",
  competition: "ליגה א' דרום",
  round: 6,
  ownSide: "AWAY",
  homeTeamName: "מ.ס. דימונה",
  awayTeamName: "מ.כ. שדרות",
  homeScore: 4,
  awayScore: 1,
  players: [
    {
      playerId: 208837,
      name: "אורי חנניה לוי",
      shirtNumber: 55,
      role: "STARTER",
      goalkeeper: true,
      captain: false,
      events: [],
    },
    {
      playerId: 116071,
      name: "בנימין נתן",
      shirtNumber: 6,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_OFF", minute: 50 }],
    },
    {
      playerId: 72977,
      name: "ליאל זגורי",
      shirtNumber: 18,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [],
    },
    {
      playerId: 126376,
      name: "רואי אביטן",
      shirtNumber: 19,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_OFF", minute: 62 }],
    },
    {
      playerId: 89070,
      name: "עידן וינטראוב",
      shirtNumber: 23,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [],
    },
    {
      playerId: 48702,
      name: "ירדן משה אבוחצירה",
      shirtNumber: 24,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_OFF", minute: 76 }],
    },
    {
      playerId: 115196,
      name: "עידן אהדנני",
      shirtNumber: 25,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_OFF", minute: 21 }],
    },
    {
      playerId: 120427,
      name: "מור עדרי",
      shirtNumber: 36,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [],
    },
    {
      playerId: 120041,
      name: "יואב צור שדי",
      shirtNumber: 71,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_OFF", minute: 57 }],
    },
    {
      playerId: 166984,
      name: "עמאר אלדדה",
      shirtNumber: 88,
      role: "STARTER",
      goalkeeper: false,
      captain: false,
      events: [
        { kind: "GOAL", minute: 38 },
        { kind: "RED", minute: 40 },
      ],
    },
    {
      playerId: 34238,
      name: "תומר לוי",
      shirtNumber: 99,
      role: "STARTER",
      goalkeeper: false,
      captain: true,
      events: [{ kind: "YELLOW", minute: 66 }],
    },
    {
      playerId: 166293,
      name: "דוד טיוליאבקו",
      shirtNumber: 17,
      role: "SUBSTITUTE",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_ON", minute: 21 }],
    },
    {
      playerId: 105894,
      name: "תום איידה",
      shirtNumber: 77,
      role: "SUBSTITUTE",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_ON", minute: 50 }],
    },
    {
      playerId: 65679,
      name: "רוסלן קוזניצוב",
      shirtNumber: 9,
      role: "SUBSTITUTE",
      goalkeeper: false,
      captain: false,
      events: [
        { kind: "SUB_ON", minute: 57 },
        { kind: "YELLOW", minute: 84 },
      ],
    },
    {
      playerId: 166304,
      name: "דניאל דוד צדיק",
      shirtNumber: 7,
      role: "SUBSTITUTE",
      goalkeeper: false,
      captain: false,
      events: [
        { kind: "SUB_ON", minute: 62 },
        { kind: "YELLOW", minute: 77 },
      ],
    },
    {
      playerId: 120863,
      name: "רון שלום יוסף",
      shirtNumber: 30,
      role: "SUBSTITUTE",
      goalkeeper: false,
      captain: false,
      events: [{ kind: "SUB_ON", minute: 76 }],
    },
    {
      playerId: 113967,
      name: "כרמל יונה",
      shirtNumber: 2,
      role: "UNUSED",
      goalkeeper: false,
      captain: false,
      events: [],
    },
    {
      playerId: 188824,
      name: "אופיר דודי",
      shirtNumber: 3,
      role: "UNUSED",
      goalkeeper: false,
      captain: false,
      events: [],
    },
    {
      playerId: 175492,
      name: "לירון שרעבי",
      shirtNumber: 20,
      role: "UNUSED",
      goalkeeper: false,
      captain: false,
      events: [],
    },
    {
      playerId: 89699,
      name: "יחזקל אור מזרחי",
      shirtNumber: 27,
      role: "UNUSED",
      goalkeeper: false,
      captain: false,
      events: [],
    },
  ],
};

describe("game details: the four fixtures", () => {
  test("1122670 (Dimona–Sderot, away): the whole output", () => {
    assert.deepEqual(parse(1122670), { game: GAME_1122670, warnings: [] });
  });

  test("each fixture: side, competition, round, names, scores, players per role, no warning", () => {
    const expected = {
      1122670: ["AWAY", "ליגה א' דרום", 6, "מ.ס. דימונה", "מ.כ. שדרות", 4, 1, [11, 5, 4]],
      1143937: ["HOME", "ליגה א' דרום", 5, "מ.כ. שדרות", "הפועל אזור", 0, 0, [11, 5, 4]],
      1122634: [
        "HOME",
        "ליגה א' דרום",
        2,
        "מ.כ. שדרות",
        "מכבי קרית מלאכי עירוני",
        1,
        2,
        [11, 5, 4],
      ],
      1142007: ["AWAY", "גביע המדינה ווינר", 4, "הפועל רהט", "מ.כ. שדרות", 1, 4, [11, 5, 1]],
    };
    for (const [id, [side, competition, round, home, away, hs, as, counts]] of Object.entries(
      expected,
    )) {
      const { game, warnings } = parse(Number(id));
      assert.deepEqual(
        [game.ownSide, game.competition, game.round, game.homeTeamName, game.awayTeamName],
        [side, competition, round, home, away],
        id,
      );
      assert.deepEqual([game.homeScore, game.awayScore], [hs, as], id);
      assert.deepEqual(
        roles(game),
        { STARTER: counts[0], SUBSTITUTE: counts[1], UNUSED: counts[2] },
        id,
      );
      assert.deepEqual(warnings, [], id);
      assert.equal(game.date, ROW[id].date);
      assert.equal(game.kickoffTime, ROW[id].kickoffTime);
    }
  });

  test("the legend's own_goal.svg / penalty.svg (on every page) fail none of them", () => {
    for (const id of Object.keys(HTML)) {
      assert.ok(HTML[id].includes("own_goal.svg") && HTML[id].includes("penalty.svg"), id);
      assert.doesNotThrow(() => parse(Number(id)), id);
    }
  });

  test("the opponent's players never appear in the output", () => {
    for (const id of Object.keys(HTML)) {
      const json = JSON.stringify(parse(Number(id)));
      assert.ok(!json.includes(OPPONENT_NAME), id);
      assert.ok(!OPPONENT_ID.test(json), id);
      assert.ok(!json.includes("מאמן"), id); // coaches
      assert.ok(!json.includes("ביתית") && !json.includes("אורחת"), id); // the header row
    }
  });

  test("coaches and the header row aren't players: every player has a player_id link", () => {
    for (const id of Object.keys(HTML)) {
      for (const player of parse(Number(id)).game.players) {
        assert.ok(HTML[id].includes(`player_id=${player.playerId}`), `${id} ${player.playerId}`);
      }
    }
  });

  test("links with (&season_id, starters) and without (substitutes, unused) season_id", () => {
    const html = HTML[1122670];
    assert.ok(html.includes("player_id=208837&season_id=28"));
    assert.ok(html.includes('player_id=166293"'));
    const game = gameOf(1122670);
    assert.equal(playerOf(game, 208837).role, "STARTER");
    assert.equal(playerOf(game, 166293).role, "SUBSTITUTE");
  });
});

describe("game details: pinned cases", () => {
  test("(C) on an outfield player, (GK) alone; (GK) (C) and (C) (GK) via variants", () => {
    const game = gameOf(1122670);
    assert.deepEqual(
      [playerOf(game, 34238).goalkeeper, playerOf(game, 34238).captain],
      [false, true],
    );
    assert.deepEqual(
      [playerOf(game, 208837).goalkeeper, playerOf(game, 208837).captain],
      [true, false],
    );
    for (const suffix of ["- (GK) (C)", "- (C) (GK)"]) {
      const html = inPlayer(HTML[1122670], 208837, (block) => swap(block, "- (GK) ", suffix));
      const keeper = playerOf(gameOf(1122670, html), 208837);
      assert.deepEqual([keeper.goalkeeper, keeper.captain], [true, true], suffix);
    }
  });

  test("a straight red after a goal for the same player", () => {
    assert.deepEqual(playerOf(gameOf(1122670), 166984).events, [
      { kind: "GOAL", minute: 38 },
      { kind: "RED", minute: 40 },
    ]);
  });

  test("yellow then yellow2 (the opponent's markup, copied to one of ours) → YELLOW + SECOND_YELLOW", () => {
    const opponent = `<span class="moves"><span class='yellow'><span class="sr-only tooltip">כרטיס צהוב</span><span class="sr-only">דקה</span>10</span><span class='yellow2'><span class="sr-only tooltip">2 כרטיס צהוב</span><span class="sr-only">דקה</span>88</span></span>`;
    assert.ok(HTML[1143937].includes(opponent));
    const html = inPlayer(HTML[1122670], 72977, (block) =>
      swap(block, '<span class="moves"></span>', opponent),
    );
    assert.deepEqual(playerOf(gameOf(1122670, html), 72977).events, [
      { kind: "YELLOW", minute: 10 },
      { kind: "SECOND_YELLOW", minute: 88 },
    ]);
  });

  test("minute -1 → null, sorted last; two goals by one player; minutes after 90", () => {
    const game = gameOf(1142007);
    const withUnknown = game.players.find((p) => p.events.some((e) => e.minute === null));
    assert.deepEqual(withUnknown.events, [
      { kind: "SUB_ON", minute: 57 },
      { kind: "YELLOW", minute: null },
    ]);
    const twoGoals = game.players.find(
      (p) => p.events.filter((e) => e.kind === "GOAL").length === 2,
    );
    assert.deepEqual(twoGoals.events, [
      { kind: "SUB_ON", minute: 75 },
      { kind: "GOAL", minute: 88 },
      { kind: "GOAL", minute: 92 },
    ]);
    assert.ok(game.players.every((p) => p.events.every((e) => e.minute !== -1)));
  });

  test("events are sorted by minute, unknown last, even if the page lists them otherwise", () => {
    const html = inPlayer(HTML[1122670], 65679, (block) =>
      swap(block, "</span>57</span>", "</span>-1</span>").replace(
        "</span>84</span>",
        "</span>3</span>",
      ),
    );
    assert.deepEqual(playerOf(gameOf(1122670, html), 65679).events, [
      { kind: "YELLOW", minute: 3 },
      { kind: "SUB_ON", minute: null },
    ]);
  });

  test("minutes 91–98 are kept as plain numbers", () => {
    const html = inPlayer(HTML[1122670], 65679, (block) =>
      swap(block, "</span>84</span>", "</span>98</span>"),
    );
    assert.equal(playerOf(gameOf(1122670, html), 65679).events[1].minute, 98);
    const minutes = gameOf(1142007).players.flatMap((p) => p.events.map((e) => e.minute));
    assert.ok(minutes.includes(92));
  });

  test("a masked name on our side (variant; the real one is the opponent's) → null", () => {
    assert.ok(HTML[1143937].includes("<b>*****</b>"));
    const html = inPlayer(HTML[1122670], 34238, (block) =>
      swap(block, "<b>תומר לוי</b>", "<b>*****</b>"),
    );
    const player = playerOf(gameOf(1122670, html), 34238);
    assert.equal(player.name, null);
    assert.equal(player.captain, true);
  });

  test("a 1-player bench", () => {
    assert.equal(roles(gameOf(1142007)).UNUSED, 1);
  });

  test("an empty shirt number → null", () => {
    const html = inPlayer(HTML[1122670], 34238, (block) => swap(block, "מס' 99", ""));
    assert.equal(playerOf(gameOf(1122670, html), 34238).shirtNumber, null);
  });
});

describe("game details: ParseError", () => {
  const H = HTML[1122670];
  const cases = {
    "our goal with an own-goal icon": [
      inPlayer(H, 166984, (b) => swap(b, "football.svg", "own_goal.svg")),
      /unknown event icon: own_goal\.svg/,
    ],
    "our goal with a penalty icon": [
      inPlayer(H, 166984, (b) => swap(b, "football.svg", "penalty.svg")),
      /unknown event icon: penalty\.svg/,
    ],
    "an own-goal icon in a timeline event node only": [
      swap(
        H,
        "src = 'SVTemplate/img/League/football.svg'",
        "src = 'SVTemplate/img/general/he/own_goal.svg'",
      ),
      /unknown timeline icon: own_goal\.svg/,
    ],
    "a goal's sr-only text other than שער": [
      inPlayer(H, 166984, (b) =>
        swap(b, '<span class="sr-only">שער</span>', '<span class="sr-only">שער עצמי</span>'),
      ),
      /sr-only label שער/,
    ],
    "an unknown span class in moves": [
      inPlayer(H, 34238, (b) => swap(b, "class='yellow'", "class='orange'")),
      /unknown event in moves: span\.orange/,
    ],
    "an unknown element in moves": [
      inPlayer(H, 34238, (b) =>
        swap(b, "<span class='yellow'>", "<i class='yellow'>").replace("66</span>", "66</i>"),
      ),
      /unknown event in moves: i\.yellow/,
    ],
    "text in moves outside an event": [
      inPlayer(H, 120427, (b) =>
        swap(b, '<span class="moves"></span>', '<span class="moves">x</span>'),
      ),
      /unexpected text in moves/,
    ],
    "header team_id matching neither side": [
      swap(H, "team_id=7617&season_id=28'", "team_id=7618&season_id=28'"),
      /neither header side/,
    ],
    "header team_id matching both sides": [
      swap(H, "team_id=5013", "team_id=7617"),
      /both header sides/,
    ],
    "no gfd_ section for the requested id": [
      swap(H, 'id="gfd_1122670"', 'id="gfd_1122671"'),
      /no game section/,
    ],
    'data-onlive="True"': [
      swap(H, 'data-onlive="False"', 'data-onlive="True"'),
      /isn't marked finished/,
    ],
    "teams-names without past": [
      swap(H, 'class="teams-names  past "', 'class="teams-names"'),
      /isn't marked finished/,
    ],
    "gTimeHolder without past": [
      swap(H, "class='game-time past'", "class='game-time'"),
      /isn't marked finished/,
    ],
    "an h1 without מחזור": [swap(H, "ליגה א' דרום מחזור 6", "ליגה א' דרום 6"), /h1 isn't/],
    "round 0": [swap(H, "מחזור 6", "מחזור 0"), /round outside/],
    "round 100": [swap(H, "מחזור 6", "מחזור 100"), /round outside/],
    "a score that isn't two integers": [swap(H, "</i>4 : <i", "</i>x : <i"), /score isn't/],
    "a link without player_id": [
      swap(H, "?player_id=34238&season_id=28", "?season_id=28"),
      /without a valid player_id/,
    ],
    "a duplicate player_id": [swap(H, "player_id=116071", "player_id=208837"), /appears twice/],
    "מס' X": [inPlayer(H, 34238, (b) => swap(b, "מס' 99", "מס' X")), /shirt number/],
    "מס' 100": [inPlayer(H, 34238, (b) => swap(b, "מס' 99", "מס' 100")), /shirt number/],
    "an unknown name suffix": [
      inPlayer(H, 34238, (b) => swap(b, "</b> - (C)", "</b> - (VC)")),
      /unknown name suffix/,
    ],
    "an empty name": [
      inPlayer(H, 34238, (b) => swap(b, "<b>תומר לוי</b>", "<b> </b>")),
      /empty player name/,
    ],
    "a minute of 151": [
      inPlayer(H, 34238, (b) => swap(b, "</span>66</span>", "</span>151</span>")),
      /outside 0\.\.150/,
    ],
    "a minute of -2": [
      inPlayer(H, 34238, (b) => swap(b, "</span>66</span>", "</span>-2</span>")),
      /isn't an integer/,
    ],
    "a goal without a minute": [
      inPlayer(H, 166984, (b) =>
        swap(b, '<span class="sr-only">דקה</span>38 ', '<span class="sr-only">דקה</span>'),
      ),
      /without a minute/,
    ],
  };
  for (const [name, [html, pattern]] of Object.entries(cases)) {
    test(name, () => {
      assertParseError(() => parse(1122670, html), pattern);
    });
  }

  test("the row cross-check: names swapped, a name or a score different", () => {
    const row = ROW[1122670];
    const variants = {
      homeTeamName: { ...row, homeTeamName: row.awayTeamName, awayTeamName: row.homeTeamName },
      awayTeamName: { ...row, awayTeamName: "מ.כ. שדרות ב" },
      homeScore: { ...row, homeScore: 3 },
      awayScore: { ...row, awayScore: 2 },
    };
    for (const [field, variant] of Object.entries(variants)) {
      assertParseError(() => parse(1122670, H, variant), new RegExp(`^${field} differs`));
    }
  });

  test("no ParseError message contains a name, ours or the opponent's", () => {
    const names = gameOf(1122670).players.map((p) => p.name);
    for (const [name, [html]] of Object.entries(cases)) {
      try {
        parse(1122670, html);
      } catch (error) {
        for (const n of [...names, OPPONENT_NAME, "מ.כ. שדרות", "מ.ס. דימונה"]) {
          assert.ok(!error.message.includes(n), `${name}: ${error.message}`);
        }
      }
    }
  });
});

describe("game details: warnings, never failures", () => {
  test("10 starters", () => {
    const { game, warnings } = parse(1122670, withoutPlayer(HTML[1122670], 72977));
    assert.equal(roles(game).STARTER, 10);
    assert.deepEqual(warnings, [{ check: "starters", expected: 11, actual: 10 }]);
  });

  test("no goalkeeper among the starters", () => {
    const html = inPlayer(HTML[1122670], 208837, (b) =>
      swap(b, "</b> - (GK) </span>", "</b></span>"),
    );
    assert.deepEqual(parse(1122670, html).warnings, [
      { check: "startingGoalkeeper", expected: 1, actual: 0 },
    ]);
  });

  test("our goals ≠ our score", () => {
    const html = inPlayer(HTML[1122670], 166984, (b) =>
      swap(
        b,
        `<img src='SVTemplate/img/League/football.svg' class='svg-img' alt='' /><span class="sr-only">שער</span><span class="sr-only">דקה</span>38 `,
        "",
      ),
    );
    const { game, warnings } = parse(1122670, html);
    assert.deepEqual(playerOf(game, 166984).events, [{ kind: "RED", minute: 40 }]);
    assert.deepEqual(warnings, [{ check: "goalsVsScore", expected: 1, actual: 0 }]);
  });

  test("subs on ≠ subs off", () => {
    const html = inPlayer(HTML[1122670], 116071, (b) =>
      swap(
        b,
        `<span class='change-down'><span class="sr-only">יצא</span><span class="sr-only">דקה</span>50</span>`,
        "",
      ),
    );
    assert.deepEqual(parse(1122670, html).warnings, [
      { check: "subsOnVsOff", expected: 4, actual: 5 },
    ]);
  });
});

describe("cup rows (KAN-85): checked by ownSide and the 90-minute score, never by names", () => {
  const cupRow = (natcup, ownName, gameId) => {
    const row = parseCupGames(fixture(natcup), ownName).games.find((g) => g.gameId === gameId);
    assert.ok(row, `no cup row ${gameId}`);
    return row;
  };
  const SDEROT = "מועדון כדורגל שדרות";
  const ROW_1142007 = cupRow("natcup-7617-s28.xml", SDEROT, 1142007);
  const ROW_1123491 = cupRow("natcup-7617-s28.xml", SDEROT, 1123491);
  const ROW_1123493 = cupRow("natcup-2194-s28.xml", "הפועל ניר רמת השרון", 1123493);
  const ROW_1123518 = cupRow("natcup-2338-s28.xml", "הפועל בני מוסמוס", 1123518);
  const cup = (gameId, row, { teamId = 7617, html = fixture(`game-${gameId}.html`) } = {}) =>
    parseGameDetails(html, { gameId, teamId, row, kind: "CUP" });

  test("game-1142007 against its cup row: passes with different (full) names; the page's names are sent", () => {
    assert.notEqual(ROW_1142007.awayTeamName, "מ.כ. שדרות");
    const { game } = cup(1142007, ROW_1142007);
    assert.deepEqual(
      [game.ownSide, game.homeTeamName, game.awayTeamName, game.homeScore, game.awayScore],
      ["AWAY", "הפועל רהט", "מ.כ. שדרות", 1, 4],
    );
    assert.deepEqual([game.competition, game.round], ["גביע המדינה ווינר", 4]);
    assert.deepEqual([game.date, game.kickoffTime], [ROW_1142007.date, ROW_1142007.kickoffTime]);
    assert.deepEqual(Object.keys(game), Object.keys(gameOf(1142007)));
  });

  test("the same full-name row as a LEAGUE row still fails on the names (unchanged)", () => {
    assert.throws(
      () =>
        parseGameDetails(HTML[1142007], {
          gameId: 1142007,
          teamId: 7617,
          row: ROW_1142007,
          kind: "LEAGUE",
        }),
      { message: "awayTeamName differs from the team-games row" },
    );
  });

  test("a cup row with the other ownSide, or another score → ParseError", () => {
    assert.throws(() => cup(1142007, { ...ROW_1142007, ownSide: "HOME" }), {
      message: "ownSide differs from the cup row",
    });
    assert.throws(() => cup(1142007, { ...ROW_1142007, homeScore: 4, awayScore: 1 }), {
      message: "homeScore differs from the cup row",
    });
    assert.throws(() => cup(1142007, { ...ROW_1142007, awayScore: 5 }), {
      message: "awayScore differs from the cup row",
    });
  });

  test("a missing or unknown kind is a programming error, not a ParseError", () => {
    for (const kind of [undefined, "cup"]) {
      assert.throws(
        () =>
          parseGameDetails(HTML[1142007], {
            gameId: 1142007,
            teamId: 7617,
            row: ROW_1142007,
            kind,
          }),
        (error) => error instanceof TypeError,
      );
    }
  });

  test("game-1123491, a preliminary round: its h1 is 'גביע המדינה ווינר מחזור 2' and it parses", () => {
    const { game } = cup(1123491, ROW_1123491);
    assert.deepEqual(
      [game.competition, game.round, game.ownSide],
      ["גביע המדינה ווינר", 2, "HOME"],
    );
    assert.deepEqual([game.homeScore, game.awayScore], [1, 0]);
    assert.equal(game.players.length, 19);
  });

  test("game-1123493 (extra time): the page's main score is the 90-minute one", () => {
    assert.deepEqual([ROW_1123493.homeScore, ROW_1123493.awayScore], [2, 2]);
    assert.deepEqual([ROW_1123493.extraTimeHomeScore, ROW_1123493.extraTimeAwayScore], [3, 2]);
    // The row check passes with the 90-minute score; the page then fails on an in-play penalty
    // (pinned as today: own goals and penalties fail a game until their markup is decided).
    assert.throws(() => cup(1123493, ROW_1123493, { teamId: 2194 }), {
      message: "unknown timeline icon: penalty.svg",
    });
    // With the after-extra-time score as the row's, it's the score check that fails.
    const afterExtraTime = { ...ROW_1123493, homeScore: 3, awayScore: 2 };
    assert.throws(() => cup(1123493, afterExtraTime, { teamId: 2194 }), {
      message: "homeScore differs from the cup row",
    });
  });

  test("game-1123518 (a shootout) is pinned as today: a PARSE_ERROR on the kick takers' penalty icons", () => {
    assert.deepEqual([ROW_1123518.penaltiesHomeScore, ROW_1123518.penaltiesAwayScore], [3, 1]);
    assert.throws(
      () => cup(1123518, ROW_1123518, { teamId: 2338 }),
      (error) => {
        assert.ok(error instanceof ParseError);
        assert.equal(error.message, "unknown event icon: penalty.svg");
        return true;
      },
    );
  });
});
