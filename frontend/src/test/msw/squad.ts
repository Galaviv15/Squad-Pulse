import { http, HttpResponse } from "msw";
import type { Player } from "@/lib/squad/types";
import { apiError } from "./auth";

/**
 * Building blocks for the squad API's answers, for tests to put together with server.use(...).
 * Not default handlers: a test that didn't expect a list request should fail on it.
 */

/** A GET /squad/players element (PlayerResponse): an active, fit CB without a photo. */
export function playerBody(overrides: Partial<Player> = {}): Player {
  return {
    id: "p1",
    fullName: "Yossi Levi",
    primaryPosition: "CB",
    secondaryPosition: null,
    jerseyNumber: 4,
    dateOfBirth: "1998-05-20",
    heightCm: 182,
    weightKg: 78,
    preferredFoot: "RIGHT",
    medicalStatus: "FIT",
    active: true,
    version: 0,
    createdAt: "2026-01-01T00:00:00Z",
    updatedAt: "2026-01-01T00:00:00Z",
    hasPhoto: false,
    ...overrides,
  };
}

interface PlayersReturnOptions {
  /** Called with each request's query parameters, e.g. to record or assert them. */
  onRequest?: (query: URLSearchParams) => void;
  /** Held until this settles. */
  gate?: Promise<void>;
}

/** GET /squad/players answering with `players` (in that order, as the server sorted them). */
export const playersReturn = (players: Player[], { onRequest, gate }: PlayersReturnOptions = {}) =>
  http.get("/squad/players", async ({ request }) => {
    onRequest?.(new URL(request.url).searchParams);
    await gate;
    return HttpResponse.json(players);
  });

/**
 * GET /squad/players recording each request's query string (as sent: "" for none) in `queries`,
 * answering with `players`.
 */
export function recordedPlayers(players: Player[] = [playerBody()]) {
  const queries: string[] = [];
  const handler = playersReturn(players, { onRequest: (query) => queries.push(query.toString()) });
  return { handler, queries };
}

/** GET /squad/players/:id answering with `player` (read on each request) for its id, else 404. */
export const playerReturns = (
  player: Player | (() => Player),
  { gate, onRequest }: { gate?: Promise<void>; onRequest?: () => void } = {},
) =>
  http.get("/squad/players/:id", async ({ params }) => {
    onRequest?.();
    await gate;
    const current = typeof player === "function" ? player() : player;
    return params.id === current.id
      ? HttpResponse.json(current)
      : apiError(404, "Not Found", "Player not found");
  });

/** A 409 with `code`, as the squad API answers it. */
export const conflict = (code: string, message = "Conflict") =>
  apiError(409, "Conflict", message, [], code);

type WriteAnswer = (body: Record<string, unknown>) => Response | Promise<Response>;

/** A write handler recording each request's JSON body in `bodies`, answering with `answer`. */
function recordedWrite(method: "post" | "put", path: string, answer: WriteAnswer) {
  const bodies: Record<string, unknown>[] = [];
  const handler = http[method](path, async ({ request }) => {
    const body = (await request.json()) as Record<string, unknown>;
    bodies.push(body);
    return answer(body);
  });
  return { handler, bodies };
}

/** POST /squad/players: by default a 201 with the player as sent, id "new1", version 0. */
export const createPlayerReturns = (
  answer: WriteAnswer = (body) =>
    HttpResponse.json(playerBody({ ...body, id: "new1", version: 0 } as Partial<Player>), {
      status: 201,
    }),
) => recordedWrite("post", "/squad/players", answer);

/** PUT /squad/players/:id: by default a 200 with the player as sent, its version moved on by one. */
export const updatePlayerReturns = (
  id: string,
  answer: WriteAnswer = (body) =>
    HttpResponse.json(
      playerBody({ ...body, id, version: Number(body.version) + 1 } as Partial<Player>),
    ),
) => recordedWrite("put", `/squad/players/${id}`, answer);
