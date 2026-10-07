import { http, HttpResponse } from "msw";
import type { Player, SquadSummary } from "@/lib/squad/types";
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

/**
 * POST /squad/players/:id/release for `player`: by default a 200 with the player released, its
 * version moved on by one.
 */
export const releaseReturns = (
  player: Player,
  answer: WriteAnswer = (body) =>
    HttpResponse.json({ ...player, active: false, version: Number(body.version) + 1 }),
) => recordedWrite("post", `/squad/players/${player.id}/release`, answer);

/**
 * POST /squad/players/:id/reactivate for `player`: by default a 200 with the player active, the
 * number as sent (null for none), its version moved on by one.
 */
export const reactivateReturns = (
  player: Player,
  answer: WriteAnswer = (body) =>
    HttpResponse.json({
      ...player,
      active: true,
      jerseyNumber: (body.jerseyNumber as number | null) ?? null,
      version: Number(body.version) + 1,
    }),
) => recordedWrite("post", `/squad/players/${player.id}/reactivate`, answer);

type BodylessAnswer = () => Response | Promise<Response>;

const noContent = () => new HttpResponse(null, { status: 204 });

/** A handler without a request body, counting its requests in `requests.count`. */
function countedRequest(method: "delete", path: string, answer: BodylessAnswer) {
  const requests = { count: 0 };
  const handler = http[method](path, () => {
    requests.count += 1;
    return answer();
  });
  return { handler, requests };
}

/** DELETE /squad/players/:id: by default 204. */
export const deleteReturns = (id: string, answer: BodylessAnswer = noContent) =>
  countedRequest("delete", `/squad/players/${id}`, answer);

/** DELETE /squad/players/:id/photo: by default 204. */
export const photoDeleteReturns = (id: string, answer: BodylessAnswer = noContent) =>
  countedRequest("delete", `/squad/players/${id}/photo`, answer);

/** What a photo upload sent: the multipart parts' names, and the `file` part's name, size and type. */
export interface RecordedUpload {
  partNames: string[];
  fileName: string | null;
  size: number | null;
  type: string | null;
}

/** PUT /squad/players/:id/photo recording each upload in `uploads`: by default 204. */
export function photoUploadReturns(id: string, answer: BodylessAnswer = noContent) {
  const uploads: RecordedUpload[] = [];
  const handler = http.put(`/squad/players/${id}/photo`, async ({ request }) => {
    const form = await request.formData();
    const file = form.get("file");
    const isFile = typeof file === "object" && file !== null;
    uploads.push({
      partNames: [...form.keys()],
      fileName: isFile ? file.name : null,
      size: isFile ? file.size : null,
      type: isFile ? file.type : null,
    });
    return answer();
  });
  return { handler, uploads };
}

/** A GET /squad/summary body (SquadSummaryResponse): 23 active players, 3 / 8 / 7 / 5 by line. */
export function summaryBody(overrides: Partial<SquadSummary> = {}): SquadSummary {
  return {
    playerCount: 23,
    averageAge: 26.4,
    lines: { GOALKEEPERS: 3, DEFENSE: 8, MIDFIELD: 7, ATTACK: 5 },
    ...overrides,
  };
}

type SummaryAnswer = SquadSummary | (() => Response | Promise<Response>);

/**
 * GET /squad/summary, which the dashboard (/app) requests on every visit: so any test that lands
 * on /app needs this. Answers with `answer`, a body or a function (read on each request, e.g. an
 * error, or the next of several answers).
 */
export const summaryReturns = (
  answer: SummaryAnswer = summaryBody(),
  { gate, onRequest }: { gate?: Promise<void>; onRequest?: () => void } = {},
) =>
  http.get("/squad/summary", async () => {
    onRequest?.();
    await gate;
    return typeof answer === "function" ? answer() : HttpResponse.json(answer);
  });
