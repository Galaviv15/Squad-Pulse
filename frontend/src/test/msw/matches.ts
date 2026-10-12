import { http, HttpResponse } from "msw";
import type {
  Fixtures,
  Game,
  LeagueTable,
  LeagueTableRow,
  RefreshStatus,
} from "@/lib/matches/types";

/**
 * Building blocks for the matches API's answers, for tests to put together with server.use(...).
 * Not default handlers: a test that didn't expect a request should fail on it. The bodies are
 * shaped exactly as the backend sends them (Jackson, checked against the real backend in KAN-69):
 * instants as ISO-8601 UTC strings with milliseconds ("2026-10-12T16:25:45.635Z"; Instant.toString
 * drops a zero fraction), `date` yyyy-MM-dd, `kickoffTime` HH:mm, and every field present, null
 * when empty (an unset kickoffTime or venue is null, never absent).
 */

/** A game of GET /matches/fixtures: an upcoming home game with a time and a venue. */
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
    ...overrides,
  };
}

/** A played game: our team won 3–1 at home. */
export const playedGame = (overrides: Partial<Game> = {}) =>
  gameBody({ ownScore: 3, opponentScore: 1, result: "WIN", ...overrides });

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
