import { http, HttpResponse } from "msw";
import type {
  Fixtures,
  Game,
  GameAppearance,
  LeagueTable,
  LeagueTableRow,
  PlayedGameDetails,
  RefreshStatus,
} from "@/lib/matches/types";
import { apiError } from "./auth";

/**
 * Building blocks for the matches API's answers, for tests to put together with server.use(...).
 * Not default handlers: a test that didn't expect a request should fail on it. The bodies are
 * shaped exactly as the backend sends them (Jackson, checked against the real backend in KAN-69):
 * instants as ISO-8601 UTC strings with milliseconds ("2026-10-12T16:25:45.635Z"; Instant.toString
 * drops a zero fraction), `date` yyyy-MM-dd, `kickoffTime` HH:mm, and every field present, null
 * when empty (an unset kickoffTime or venue is null, never absent).
 */

/** A game of GET /matches/fixtures: an upcoming home league game with a time and a venue. */
export function gameBody(overrides: Partial<Game> = {}): Game {
  return {
    date: "2026-10-17",
    kickoffTime: "20:00",
    ownSide: "HOME",
    opponentName: "Hapoel Ashdod",
    venue: "Sderot Municipal",
    ownScore: null,
    opponentScore: null,
    result: null,
    detailsId: null,
    detailsState: null,
    competition: "LEAGUE",
    round: null,
    extraTime: null,
    penalties: null,
    decidedBy: null,
    ...overrides,
  };
}

/** A played game: our team won 3–1 at home; its details stored (id "details-<date>"). */
export const playedGame = (overrides: Partial<Game> = {}) =>
  gameBody({
    ownScore: 3,
    opponentScore: 1,
    result: "WIN",
    detailsState: "AVAILABLE",
    detailsId: `details-${overrides.date ?? "2026-10-17"}`,
    ...overrides,
  });

/*
 * State Cup games (KAN-84 / KAN-86), made-up opponents. Scores are from our side, as the backend
 * sends them (ownScore / opponentScore the 90 minutes; extraTime, penalties likewise); a test that
 * cares about home / away order passes `ownSide`. Each played one has its details stored.
 */

/** An upcoming State Cup game at home, round "ו". */
export const cupGame = (overrides: Partial<Game> = {}) =>
  gameBody({
    competition: "STATE_CUP",
    round: "ו",
    opponentName: "Hapoel Netivot",
    venue: null,
    ...overrides,
  });

const playedCupGame = (overrides: Partial<Game>) =>
  cupGame({
    detailsState: "AVAILABLE",
    detailsId: `details-${overrides.date ?? "2026-10-17"}`,
    ...overrides,
  });

/** Won 2–0 in 90 minutes. */
export const cupGameIn90 = (overrides: Partial<Game> = {}) =>
  playedCupGame({ ownScore: 2, opponentScore: 0, result: "WIN", ...overrides });

/** 2–2 after 90 minutes, won 3–2 after extra time. */
export const cupGameAfterExtraTime = (overrides: Partial<Game> = {}) =>
  playedCupGame({
    ownScore: 2,
    opponentScore: 2,
    extraTime: { ownScore: 3, opponentScore: 2 },
    decidedBy: "EXTRA_TIME",
    result: "WIN",
    ...overrides,
  });

/** 3–3 after 90 minutes and after extra time, lost 1–3 on penalties. */
export const cupGameOnPenalties = (overrides: Partial<Game> = {}) =>
  playedCupGame({
    ownScore: 3,
    opponentScore: 3,
    extraTime: { ownScore: 3, opponentScore: 3 },
    penalties: { ownScore: 1, opponentScore: 3 },
    decidedBy: "PENALTIES",
    result: "LOSS",
    ...overrides,
  });

/** Played (1–1) but without a result: a level shootout, which the backend sends as null (bad data). */
export const cupGameWithoutResult = (overrides: Partial<Game> = {}) =>
  playedCupGame({
    ownScore: 1,
    opponentScore: 1,
    penalties: { ownScore: 4, opponentScore: 4 },
    decidedBy: "PENALTIES",
    result: null,
    ...overrides,
  });

/** A GET /matches/fixtures body: OK, two played games and two upcoming ones. */
export function fixturesBody(overrides: Partial<Fixtures> = {}): Fixtures {
  return {
    state: "OK",
    lastUpdatedAt: "2026-10-12T15:30:45.635Z",
    teamName: "Hapoel Sderot",
    mappingProblem: null,
    games: [
      playedGame({ date: "2026-09-05", opponentName: "Maccabi Yavne" }),
      playedGame({
        date: "2026-09-12",
        ownSide: "AWAY",
        opponentName: "Beitar Kiryat Gat",
        ownScore: 0,
        opponentScore: 2,
        result: "LOSS",
      }),
      gameBody({ date: "2026-10-17", opponentName: "Hapoel Ashdod" }),
      gameBody({ date: "2026-10-24", ownSide: "AWAY", opponentName: "Ironi Ofakim" }),
    ],
    ...overrides,
  };
}

/** A read without data (NOT_MAPPED or NO_DATA), as the backend sends it. */
export function emptyFixtures(state: "NOT_MAPPED" | "NO_DATA"): Fixtures {
  return { state, lastUpdatedAt: null, teamName: null, mappingProblem: null, games: [] };
}

export function emptyLeagueTable(state: "NOT_MAPPED" | "NO_DATA"): LeagueTable {
  return { state, lastUpdatedAt: null, round: null, mappingProblem: null, rows: [] };
}

/** A league-table row at `position`; "Team <position>" unless named. */
export function tableRow(
  position: number,
  overrides: Partial<LeagueTableRow> = {},
): LeagueTableRow {
  return {
    position,
    teamName: `Team ${position}`,
    played: 6,
    won: 3,
    drawn: 1,
    lost: 2,
    goalsFor: 10,
    goalsAgainst: 7,
    goalDifference: 3,
    points: 30 - position,
    ownTeam: false,
    ...overrides,
  };
}

/** A GET /matches/league-table body: OK, 14 rows, our team 9th. */
export function leagueTableBody(overrides: Partial<LeagueTable> = {}): LeagueTable {
  return {
    state: "OK",
    lastUpdatedAt: "2026-10-11T09:05:54.188Z",
    round: 6,
    mappingProblem: null,
    rows: Array.from({ length: 14 }, (_, i) =>
      i === 8 ? tableRow(9, { teamName: "Hapoel Sderot", ownTeam: true }) : tableRow(i + 1),
    ),
    ...overrides,
  };
}

/** One of our players in a game (GET /matches/games/{id}): a starter, unlinked, no events. */
export function appearanceBody(overrides: Partial<GameAppearance> = {}): GameAppearance {
  return {
    name: "Player",
    linkedPlayer: null,
    shirtNumber: null,
    role: "STARTER",
    goalkeeper: false,
    captain: false,
    events: [],
    ...overrides,
  };
}

const starter = (shirtNumber: number, name: string, overrides: Partial<GameAppearance> = {}) =>
  appearanceBody({ name, shirtNumber, ...overrides });

/**
 * A GET /matches/games/{id} body, shaped as the backend sends it (KAN-79): the 5 Sep home win over
 * Maccabi Yavne (playedGame's id), round 6. 11 starters (a GK, a captain), 3 substitutes, 4 unused
 * (a GK, a masked name, a missing shirt number); linked players (one released); every event kind:
 * two goals by one player, a yellow then a second yellow for one player, a sub off and on at the
 * same minute (60), a minute of 93, an unknown minute (last, as the backend sorts). The starters'
 * shirt numbers are deliberately not in order. Made-up names.
 */
export function gameDetailsBody(overrides: Partial<PlayedGameDetails> = {}): PlayedGameDetails {
  return {
    id: "details-2026-09-05",
    date: "2026-09-05",
    kickoffTime: "19:30",
    competition: "ליגה א' דרום",
    round: 6,
    ownSide: "HOME",
    opponentName: "Maccabi Yavne",
    ownScore: 3,
    opponentScore: 1,
    result: "WIN",
    extraTime: null,
    penalties: null,
    decidedBy: null,
    competitionType: null,
    cupRound: null,
    lastUpdatedAt: "2026-10-12T15:30:45.635Z",
    players: [
      starter(1, "Avi Golan", { goalkeeper: true }),
      starter(9, "Dan Mizrahi", {
        linkedPlayer: { id: "p-9", fullName: "Daniel Mizrahi", active: true },
        events: [
          { kind: "GOAL", minute: 23 },
          { kind: "GOAL", minute: 93 },
        ],
      }),
      starter(4, "Eli Peretz", { captain: true }),
      starter(5, "Gil Levi", {
        events: [
          { kind: "YELLOW", minute: 34 },
          { kind: "SECOND_YELLOW", minute: 81 },
        ],
      }),
      starter(2, "Hen Cohen"),
      starter(3, "Itay Biton", {
        linkedPlayer: { id: "p-3", fullName: "Itay Bitton", active: false },
      }),
      starter(6, "Kfir Azulay", { events: [{ kind: "SUB_OFF", minute: 60 }] }),
      starter(8, "Lior Dahan"),
      starter(10, "Moti Hadad", { events: [{ kind: "RED", minute: 70 }] }),
      starter(11, "Nir Ohana", { events: [{ kind: "GOAL", minute: null }] }),
      starter(7, "Omer Shalom", { events: [{ kind: "SUB_OFF", minute: 75 }] }),
      starter(14, "Ran Tal", {
        role: "SUBSTITUTE",
        events: [
          { kind: "SUB_ON", minute: 60 },
          { kind: "GOAL", minute: 88 },
          { kind: "YELLOW", minute: 90 },
        ],
      }),
      starter(17, "Shai Amar", { role: "SUBSTITUTE", events: [{ kind: "SUB_ON", minute: 75 }] }),
      starter(19, "Tal Ben David", {
        role: "SUBSTITUTE",
        events: [{ kind: "SUB_ON", minute: null }],
      }),
      starter(22, "Uri Katz", { role: "UNUSED", goalkeeper: true }),
      starter(15, "Yoav Segal", { role: "UNUSED" }),
      appearanceBody({ name: null, shirtNumber: 16, role: "UNUSED" }),
      appearanceBody({ name: "Zohar Navon", shirtNumber: null, role: "UNUSED" }),
    ],
    ...overrides,
  };
}

/**
 * A State Cup game's details (KAN-86): the page's own heading (with the sponsor) and "מחזור 4",
 * the cup list's round "ו", 2–2 after 90 minutes, our 1–3 shootout loss after a 3–3 extra time.
 */
export const cupGameDetailsBody = (overrides: Partial<PlayedGameDetails> = {}) =>
  gameDetailsBody({
    competition: "גביע המדינה ווינר",
    round: 4,
    opponentName: "Hapoel Netivot",
    ownScore: 2,
    opponentScore: 2,
    extraTime: { ownScore: 3, opponentScore: 3 },
    penalties: { ownScore: 1, opponentScore: 3 },
    decidedBy: "PENALTIES",
    result: "LOSS",
    competitionType: "STATE_CUP",
    cupRound: "ו",
    ...overrides,
  });

/**
 * GET /matches/games/:id: `answer` (a body or a function) for every id, or (without one) the
 * backend's 404 for any id but gameDetailsBody()'s; `requests` counts them.
 */
export function gameDetailsReturns(answer?: Answer<PlayedGameDetails>) {
  const requests = { count: 0, ids: [] as string[] };
  const handler = http.get("/matches/games/:id", ({ params }) => {
    requests.count++;
    requests.ids.push(String(params.id));
    if (answer !== undefined) {
      return typeof answer === "function" ? answer() : HttpResponse.json(answer);
    }
    return params.id === gameDetailsBody().id
      ? HttpResponse.json(gameDetailsBody())
      : gameNotFound();
  });
  return { handler, requests };
}

/** The backend's 404 for an unknown game (match.GameDetailsReadService's NotFoundException). */
export const gameNotFound = () => apiError(404, "Not Found", "Game details not found");

type Answer<T> = T | (() => Response | Promise<Response>);

interface ReturnOptions {
  /** Called on each request, e.g. to count them. */
  onRequest?: () => void;
  /** Held until this settles. */
  gate?: Promise<void>;
}

type Body = Fixtures | LeagueTable | RefreshStatus;

const respond = (answer: Answer<Body>) =>
  typeof answer === "function" ? answer() : HttpResponse.json(answer);

/** GET /matches/fixtures answering with `answer`, a body or a function (read on each request). */
export const fixturesReturns = (
  answer: Answer<Fixtures> = fixturesBody(),
  { onRequest, gate }: ReturnOptions = {},
) =>
  http.get("/matches/fixtures", async () => {
    onRequest?.();
    await gate;
    return respond(answer);
  });

/** GET /matches/league-table answering with `answer`. */
export const leagueTableReturns = (
  answer: Answer<LeagueTable> = leagueTableBody(),
  { onRequest, gate }: ReturnOptions = {},
) =>
  http.get("/matches/league-table", async () => {
    onRequest?.();
    await gate;
    return respond(answer);
  });

/**
 * Both reads the dashboard (/app) makes besides /squad/summary: any test that lands on /app needs
 * these too (by default an unmapped club, the cards' simplest state).
 */
export const dashboardMatchesReturn = (
  fixtures: Answer<Fixtures> = emptyFixtures("NOT_MAPPED"),
  table: Answer<LeagueTable> = emptyLeagueTable("NOT_MAPPED"),
) => [fixturesReturns(fixtures), leagueTableReturns(table)];

/** A GET /matches/refresh body: an idle club that may refresh now. */
export function refreshStatusBody(overrides: Partial<RefreshStatus> = {}): RefreshStatus {
  return {
    outcome: null,
    state: "IDLE",
    failureReason: null,
    startedAt: null,
    finishedAt: null,
    nextRefreshAt: null,
    ...overrides,
  };
}

/**
 * GET /matches/refresh answering each request with the next of `answers`, then the last one again
 * (refreshStatusBody() without any); `requests` counts them.
 */
export function refreshStatusReturns(...answers: Answer<RefreshStatus>[]) {
  const list = answers.length > 0 ? answers : [refreshStatusBody()];
  const requests = { count: 0 };
  const handler = http.get("/matches/refresh", () => {
    const answer = list[Math.min(requests.count, list.length - 1)];
    requests.count++;
    return respond(answer);
  });
  return { handler, requests };
}

/**
 * POST /matches/refresh answering with `answer` (202 for STARTED, as the backend does, 200
 * otherwise; or a function), after `gate` if given; `requests` counts them.
 */
export function refreshTriggerReturns(answer: Answer<RefreshStatus>, gate?: Promise<void>) {
  const requests = { count: 0 };
  const handler = http.post("/matches/refresh", async () => {
    requests.count++;
    await gate;
    if (typeof answer === "function") {
      return answer();
    }
    return HttpResponse.json(answer, { status: answer.outcome === "STARTED" ? 202 : 200 });
  });
  return { handler, requests };
}

/** The backend's generic 500. */
export const failure500 = () =>
  apiError(500, "Internal Server Error", "An unexpected error occurred");
