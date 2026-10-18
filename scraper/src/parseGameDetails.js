// Parses one game page, our side only (KAN-75 findings sections 1–5, "Goal detection"; KAN-80).
// The opponent is read for its name and score only: its players are never read into the result.
//
// Every message is a fixed text naming a field, a class or an icon file, never a value from the
// page that could be a name: a `detail` must never carry a player's name.

import { load } from "cheerio";
import { cleanText, queryParameter } from "./parseFixtures.js";
import { ParseError, excerpt } from "./text.js";

const JAVA_INT_MAX = 2147483647;
const GOAL_ICON = "football.svg";
const H1 = /^(.+) מחזור (\d+)$/;
const SHIRT = /^מס' (\d{1,2})$/;
const MINUTE = /^(-1|\d{1,3})$/;
const SUFFIXES = new Map([
  ["", { goalkeeper: false, captain: false }],
  ["- (GK)", { goalkeeper: true, captain: false }],
  ["- (C)", { goalkeeper: false, captain: true }],
  ["- (GK) (C)", { goalkeeper: true, captain: true }],
  ["- (C) (GK)", { goalkeeper: true, captain: true }],
]);
// Our side's groups, by role class, in page order.
const GROUPS = [
  ["Active", "STARTER"],
  ["Replacement", "SUBSTITUTE"],
  ["Bench", "UNUSED"],
];
// A wrapped event in `span.moves`: its class → its kind and the `sr-only` label before "דקה".
const WRAPPED_EVENTS = new Map([
  ["yellow", { kind: "YELLOW", label: "כרטיס צהוב" }],
  ["yellow2", { kind: "SECOND_YELLOW", label: "2 כרטיס צהוב" }],
  ["red", { kind: "RED", label: "כרטיס אדום" }],
  ["change-down", { kind: "SUB_OFF", label: "יצא" }],
  ["change-up", { kind: "SUB_ON", label: "נכנס" }],
]);

/**
 * @param html the game page
 * @param gameId the id requested; the page must be that game's
 * @param teamId our team; picks our side by the header's `team_id`
 * @param row the game's row: `date` / `kickoffTime` come from it, and it's checked against the page
 *   by `kind`
 * @param kind `LEAGUE`: a team-games row ({@link parseGamesPage}), checked by both names and the
 *   score; `CUP`: a State Cup row (KAN-85), checked by `ownSide` and the 90-minute score, never by
 *   names (the list has full club names, the page short ones)
 * @returns {{game, warnings: Array<{check, expected, actual}>}}; throws ParseError
 */
export function parseGameDetails(html, { gameId, teamId, row, kind }) {
  if (kind !== "LEAGUE" && kind !== "CUP") throw new TypeError("kind must be LEAGUE or CUP");
  const $ = load(html);
  const section = $(`section#gfd_${gameId}`);
  if (section.length !== 1) throw new ParseError("no game section with the requested game id");

  const header = section.find("div.teams-names");
  const time = section.find("#gTimeHolder");
  if (
    section.attr("data-onlive") !== "False" ||
    header.length !== 1 ||
    !header.hasClass("past") ||
    time.length !== 1 ||
    !time.hasClass("past")
  ) {
    throw new ParseError("the game isn't marked finished");
  }

  const { competition, round } = parseHeading($, section);
  const home = parseTeam($, header, "div.team-home");
  const away = parseTeam($, header, "div.team-guest");
  const ownHome = home.teamId === teamId;
  const ownAway = away.teamId === teamId;
  if (ownHome === ownAway) {
    throw new ParseError(`${ownHome ? "both" : "neither"} header sides have our team_id`);
  }
  const ownSide = ownHome ? "HOME" : "AWAY";
  const [homeScore, awayScore] = parseScore($, time);

  // The page's main score is the 90-minute one (extra time and a shootout have their own lines).
  const rowName = kind === "CUP" ? "cup row" : "team-games row";
  if (kind === "CUP") {
    if (ownSide !== row.ownSide) throw new ParseError("ownSide differs from the cup row");
  } else {
    if (home.name !== row.homeTeamName) {
      throw new ParseError("homeTeamName differs from the team-games row");
    }
    if (away.name !== row.awayTeamName) {
      throw new ParseError("awayTeamName differs from the team-games row");
    }
  }
  if (homeScore !== row.homeScore) throw new ParseError(`homeScore differs from the ${rowName}`);
  if (awayScore !== row.awayScore) throw new ParseError(`awayScore differs from the ${rowName}`);

  checkTimeline($, section);
  const players = parsePlayers($, section, ownSide === "HOME" ? "home" : "guest");

  const game = {
    gameId,
    date: row.date,
    kickoffTime: row.kickoffTime,
    competition,
    round,
    ownSide,
    homeTeamName: home.name,
    awayTeamName: away.name,
    homeScore,
    awayScore,
    players,
  };
  return { game, warnings: warnings(players, ownSide === "HOME" ? homeScore : awayScore) };
}

/** `<h1>ליגה א' דרום מחזור 6 <span>פרטי המשחק</span></h1>`: the text before the `span`. */
function parseHeading($, section) {
  const h1 = section.find("h1");
  if (h1.length !== 1) throw new ParseError("expected one h1");
  const copy = h1.clone();
  copy.children("span").remove();
  const match = H1.exec(cleanText(copy.text()));
  if (!match) throw new ParseError("h1 isn't '<competition> מחזור <N>'");
  const round = Number(match[2]);
  if (round < 1 || round > 99) throw new ParseError("round outside 1..99");
  return { competition: match[1].trim(), round };
}

/** A header side: `<div class="team-home"><a href='/team-details/?team_id=…'><span>name</span>`. */
function parseTeam($, header, selector) {
  const side = header.children(selector);
  if (side.length !== 1) throw new ParseError(`expected one ${selector}`);
  const link = side.children("a[href]");
  if (link.length !== 1) throw new ParseError(`expected one team link in ${selector}`);
  const raw = queryParameter(link.attr("href"), "team_id");
  if (!/^\d{1,10}$/.test(raw ?? "")) throw new ParseError(`invalid team_id in ${selector}`);
  const name = cleanText(link.text());
  if (name === "") throw new ParseError(`empty team name in ${selector}`);
  return { teamId: Number(raw), name };
}

/** `<div class='total'><i class='sr-only'>home</i>4 : <i class='sr-only'>guest</i>1</div>`. */
function parseScore($, time) {
  const total = time.children("div.total");
  if (total.length !== 1) throw new ParseError("expected one score");
  const copy = total.clone();
  copy.find("i.sr-only").remove();
  const match = /^(\d{1,2}) ?: ?(\d{1,2})$/.exec(cleanText(copy.text()));
  if (!match) throw new ParseError("the score isn't two integers 0..99");
  return [Number(match[1]), Number(match[2])];
}

/**
 * Every icon in a timeline event node (either team's) must be the goal icon: an own goal or a
 * penalty, whichever side, fails the game until its markup is pinned. The legend
 * (`#gameMoves > div.keys`, a sibling of the timeline) always holds `own_goal.svg` and
 * `penalty.svg` and is never looked at: nodes are selected as children of `div.timeline`.
 */
function checkTimeline($, section) {
  const timeline = section.find("#gameMoves > div.timeline");
  if (timeline.length !== 1) throw new ParseError("expected one timeline");
  timeline
    .children("div.node")
    .find("img")
    .each((_, img) => {
      const file = iconFile($(img).attr("src"));
      if (file !== GOAL_ICON) throw new ParseError(`unknown timeline icon: ${excerpt(file)}`);
    });
}

function iconFile(src) {
  return (src ?? "").trim().split(/[?#]/)[0].split("/").pop();
}

/** Our side's starters, substitutes and unused players, in page order; never coaches. */
function parsePlayers($, section, sideClass) {
  const players = [];
  const ids = new Set();
  for (const [roleClass, role] of GROUPS) {
    const groups = section.find(`#teams .players-cont div.${sideClass}.${roleClass}`);
    if (groups.length > 1) throw new ParseError(`more than one ${roleClass} group`);
    if (groups.length === 0) {
      if (role === "STARTER") throw new ParseError("no starters group");
      continue;
    }
    const group = groups[0];
    // Only this group's own links, even if the page's markup nests another group inside it.
    const links = groups
      .find("a")
      .filter((_, a) => $(a).closest("div[role=group]")[0] === group)
      .toArray();
    for (const link of links) {
      const player = parsePlayer($, $(link), role);
      if (ids.has(player.playerId)) throw new ParseError("a player_id appears twice");
      ids.add(player.playerId);
      players.push(player);
    }
  }
  return players;
}

function parsePlayer($, link, role) {
  const raw = queryParameter(link.attr("href") ?? "", "player_id");
  if (!/^\d{1,10}$/.test(raw ?? "") || Number(raw) < 1 || Number(raw) > JAVA_INT_MAX) {
    throw new ParseError("a player link without a valid player_id");
  }
  const card = link.children("div.player");
  if (card.length !== 1) throw new ParseError("a player link without one div.player");

  const numbers = card.children("span.number");
  if (numbers.length > 1) throw new ParseError("more than one shirt number");
  let shirtNumber = null;
  const shirt = numbers.length === 0 ? "" : cleanText(numbers.text());
  if (shirt !== "") {
    const match = SHIRT.exec(shirt);
    if (!match) throw new ParseError("a shirt number that isn't 'מס' N'");
    shirtNumber = Number(match[1]);
  }

  const nameSpan = card.children("span.name");
  const bold = nameSpan.children("b");
  if (nameSpan.length !== 1 || bold.length !== 1)
    throw new ParseError("expected one span.name > b");
  const fullName = cleanText(bold.text());
  if (fullName === "") throw new ParseError("an empty player name");
  const rest = nameSpan.clone();
  rest.children("b").remove();
  const marks = SUFFIXES.get(cleanText(rest.text()));
  if (marks === undefined) throw new ParseError("an unknown name suffix");

  const moves = card.children("span.moves");
  if (moves.length !== 1) throw new ParseError("expected one span.moves");

  return {
    playerId: Number(raw),
    name: /^\*+$/.test(fullName) ? null : fullName,
    shirtNumber,
    role,
    goalkeeper: marks.goalkeeper,
    captain: marks.captain,
    events: sortByMinute(parseMoves($, moves)),
  };
}

/**
 * Walks `span.moves`' child nodes in order (never `.text()` on the whole: a goal's minute is a bare
 * text node). Allowed: the wrapped events of {@link WRAPPED_EVENTS}, and the bare goal
 * `<img src='…/football.svg'><span class="sr-only">שער</span><span class="sr-only">דקה</span>34`.
 * Anything else is a ParseError.
 */
function parseMoves($, moves) {
  const nodes = significant(moves.contents().toArray());
  const events = [];
  for (let i = 0; i < nodes.length;) {
    const node = nodes[i];
    if (node.type === "text") throw new ParseError("unexpected text in moves");
    const classes = classList($, node);
    if (node.name === "img") {
      const file = iconFile($(node).attr("src"));
      if (file !== GOAL_ICON) throw new ParseError(`unknown event icon: ${excerpt(file)}`);
      expectLabel($, nodes[i + 1], "שער");
      expectLabel($, nodes[i + 2], "דקה");
      events.push({ kind: "GOAL", minute: parseMinute(nodes[i + 3]) });
      i += 4;
    } else if (node.name === "span" && classes.length === 1 && WRAPPED_EVENTS.has(classes[0])) {
      const { kind, label } = WRAPPED_EVENTS.get(classes[0]);
      const inner = significant($(node).contents().toArray());
      if (inner.length !== 3) throw new ParseError(`unexpected markup in a ${classes[0]} event`);
      expectLabel($, inner[0], label);
      expectLabel($, inner[1], "דקה");
      events.push({ kind, minute: parseMinute(inner[2]) });
      i += 1;
    } else {
      throw new ParseError(
        `unknown event in moves: ${excerpt(`${node.name}.${classes.join(".")}`)}`,
      );
    }
  }
  return events;
}

/** Child nodes without comments and whitespace-only text. */
function significant(nodes) {
  return nodes.filter(
    (node) => node.type !== "comment" && !(node.type === "text" && node.data.trim() === ""),
  );
}

function classList($, node) {
  return ($(node).attr("class") ?? "").trim().split(/\s+/).filter(Boolean);
}

/** A `span.sr-only` (the tooltip variant too) whose text is exactly `label`. */
function expectLabel($, node, label) {
  const ok =
    node !== undefined &&
    node.type === "tag" &&
    node.name === "span" &&
    $(node).hasClass("sr-only") &&
    $(node).children().length === 0 &&
    cleanText($(node).text()) === label;
  if (!ok) throw new ParseError(`expected the sr-only label ${label} in moves`);
}

/** A bare text node: an integer 0..150, or -1 (unknown → null). */
function parseMinute(node) {
  if (node === undefined || node.type !== "text") throw new ParseError("an event without a minute");
  const text = cleanText(node.data);
  if (!MINUTE.test(text)) throw new ParseError("a minute that isn't an integer");
  if (text === "-1") return null;
  const minute = Number(text);
  if (minute > 150) throw new ParseError("a minute outside 0..150");
  return minute;
}

/** By minute, unknown last; stable, so same-minute events keep the page's order. */
function sortByMinute(events) {
  const key = (event) => (event.minute === null ? Infinity : event.minute);
  return [...events].sort((a, b) => (key(a) === key(b) ? 0 : key(a) < key(b) ? -1 : 1));
}

/** The counts the contract doesn't check (real pages break them): logged, never a failure. */
function warnings(players, ownScore) {
  const result = [];
  const starters = players.filter((player) => player.role === "STARTER");
  if (starters.length !== 11) {
    result.push({ check: "starters", expected: 11, actual: starters.length });
  }
  const goalkeepers = starters.filter((player) => player.goalkeeper).length;
  if (goalkeepers === 0) result.push({ check: "startingGoalkeeper", expected: 1, actual: 0 });
  const count = (kind) =>
    players.reduce((sum, player) => sum + player.events.filter((e) => e.kind === kind).length, 0);
  const goals = count("GOAL");
  if (goals !== ownScore) result.push({ check: "goalsVsScore", expected: ownScore, actual: goals });
  const on = count("SUB_ON");
  const off = count("SUB_OFF");
  if (on !== off) result.push({ check: "subsOnVsOff", expected: off, actual: on });
  return result;
}
