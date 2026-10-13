import { http, HttpResponse } from "msw";
import type { PlayerStats, PlayerStatsRow, StatsPlayerRef } from "@/lib/matches/playerStats";
import type { Player } from "@/lib/squad/types";
import { playerBody } from "./squad";

/**
 * Building blocks for the player statistics API's answers (KAN-72), for tests to put together with
 * server.use(...); not default handlers. Shaped exactly as the backend sends them
 * (PlayerStatsApiIntegrationTest): every field present in the record's order, `linkedPlayer`,
 * `suggestedPlayer` and `lastUpdatedAt` null when empty (never absent), instants as ISO-8601 UTC
 * strings. Row ids are opaque and contain "stats-row", so a test can check none is shown.
 */

export const playerRef = (player: Player): StatsPlayerRef => ({
  id: player.id,
  fullName: player.fullName,
  active: player.active,
});

/** The club's squad, for the picker (GET /squad/players?status=all), in the server's order. */
export const squadPlayers = {
  daniel: playerBody({ id: "p1", fullName: "דניאל כהן", primaryPosition: "ST", jerseyNumber: 9 }),
  yossi: playerBody({ id: "p2", fullName: "יוסי לוי", active: false, jerseyNumber: null }),
  avi: playerBody({ id: "p3", fullName: "אבי מזרחי", primaryPosition: "CM", jerseyNumber: 8 }),
  omer: playerBody({ id: "p4", fullName: "עומר פרץ", primaryPosition: "GK", jerseyNumber: 1 }),
  ron: playerBody({ id: "p5", fullName: "רון ביטון", active: false, primaryPosition: null }),
  moshe: playerBody({ id: "p6", fullName: "משה חדד", primaryPosition: "LB", jerseyNumber: 3 }),
};

export const allSquadPlayers = () => Object.values(squadPlayers);

/** A row with every number set; plain unlinked unless overridden. */
export function statsRow(id: string, name: string, overrides: Partial<PlayerStatsRow> = {}) {
  const row: PlayerStatsRow = {
    id: `stats-row-${id}`,
    name,
    games: 10,
    goals: 3,
    yellowCardsLeagueAndCup: 2,
    yellowCardsToto: 1,
    redCards: 0,
    starts: 8,
    cameOnAsSub: 2,
    subbedOff: 4,
    minutes: 712,
    linkedPlayer: null,
    suggestedPlayer: null,
    ...overrides,
  };
  return row;
}

/**
 * The fixture rows, in the site's order: a linked active player, a linked released one, a
 * suggested one, a plain unlinked one, a 0-minute row and one with minutes > 90 × games.
 */
export const statsRows = {
  linked: () =>
    statsRow("a", "כהן דניאל", { goals: 7, linkedPlayer: playerRef(squadPlayers.daniel) }),
  released: () =>
    statsRow("b", "לוי יוסי", { goals: 1, linkedPlayer: playerRef(squadPlayers.yossi) }),
  suggested: () =>
    statsRow("c", "מזרחי אבי", { goals: 12, suggestedPlayer: playerRef(squadPlayers.avi) }),
  plain: () => statsRow("d", "פרץ עומר", { goals: 0 }),
  zero: () =>
    statsRow("e", "ביטון רון", {
      games: 0,
      goals: 0,
      yellowCardsLeagueAndCup: 0,
      yellowCardsToto: 0,
      starts: 0,
      cameOnAsSub: 0,
      subbedOff: 0,
      minutes: 0,
    }),
  addedTime: () =>
    statsRow("f", "חדד משה", { games: 2, starts: 2, cameOnAsSub: 0, subbedOff: 0, minutes: 197 }),
};

/** A GET /matches/player-stats body: OK, the six fixture rows. */
export function playerStatsBody(overrides: Partial<PlayerStats> = {}): PlayerStats {
  return {
    state: "OK",
    lastUpdatedAt: "2026-10-12T15:30:45.635Z",
    players: Object.values(statsRows).map((row) => row()),
    ...overrides,
  };
}

/** A read without data, as the backend sends it. */
export function emptyPlayerStats(state: "NOT_MAPPED" | "NO_DATA"): PlayerStats {
  return { state, lastUpdatedAt: null, players: [] };
}

type Answer<T> = T | (() => Response | Promise<Response>);

const respond = (answer: Answer<PlayerStats>) =>
  typeof answer === "function" ? answer() : HttpResponse.json(answer);

/**
 * GET /matches/player-stats answering with `answer` (a body or a function, read on each request),
 * after `gate` if given; `requests` counts them.
 */
export function playerStatsReturns(
  answer: Answer<PlayerStats> = playerStatsBody(),
  { gate }: { gate?: Promise<void> } = {},
) {
  const requests = { count: 0 };
  const handler = http.get("/matches/player-stats", async () => {
    requests.count++;
    await gate;
    return respond(answer);
  });
  return { handler, requests };
}

/**
 * PUT /matches/player-stats/:id/link recording each request as { rowId, body }; by default 200
 * with a linked row, after `gate` if given.
 */
export function linkReturns(
  answer: (rowId: string, playerId: string) => Response | Promise<Response> = (rowId, playerId) =>
    HttpResponse.json(
      statsRow(rowId, "row", {
        id: rowId,
        linkedPlayer: { id: playerId, fullName: "name", active: true },
      }),
    ),
  gate?: Promise<void>,
) {
  const requests: { rowId: string; body: unknown }[] = [];
  const handler = http.put("/matches/player-stats/:id/link", async ({ params, request }) => {
    const body = (await request.json()) as { playerId: string };
    requests.push({ rowId: String(params.id), body });
    await gate;
    return answer(String(params.id), body.playerId);
  });
  return { handler, requests };
}

const noContent = () => new HttpResponse(null, { status: 204 });

/** DELETE /matches/player-stats/:id/link recording each row id; by default 204. */
export function unlinkRowReturns(answer: () => Response | Promise<Response> = noContent) {
  const requests: string[] = [];
  const handler = http.delete("/matches/player-stats/:id/link", ({ params }) => {
    requests.push(String(params.id));
    return answer();
  });
  return { handler, requests };
}

/** DELETE /squad/players/:id/stats-link counting its requests; by default 204, after `gate`. */
export function removeStatsLinkReturns(
  playerId: string,
  answer: () => Response | Promise<Response> = noContent,
  gate?: Promise<void>,
) {
  const requests = { count: 0 };
  const handler = http.delete(`/squad/players/${playerId}/stats-link`, async () => {
    requests.count++;
    await gate;
    return answer();
  });
  return { handler, requests };
}

/**
 * POST /matches/player-stats/:id/player (KAN-77) recording each request as { rowId, body }; by
 * default 201 with the player built from the body (id "new1"), after `gate` if given.
 */
export function createFromStatsReturns(
  answer: (rowId: string, body: Record<string, unknown>) => Response | Promise<Response> = (
    _rowId,
    body,
  ) => HttpResponse.json(playerBody({ ...body, id: "new1" } as Partial<Player>), { status: 201 }),
  gate?: Promise<void>,
) {
  const requests: { rowId: string; body: Record<string, unknown> }[] = [];
  const handler = http.post("/matches/player-stats/:id/player", async ({ params, request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    requests.push({ rowId: String(params.id), body });
    await gate;
    return answer(String(params.id), body);
  });
  return { handler, requests };
}
