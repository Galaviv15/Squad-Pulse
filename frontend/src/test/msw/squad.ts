import { http, HttpResponse } from "msw";
import type { Player } from "@/lib/squad/types";

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
