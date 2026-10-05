import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  accessToken,
  apiError,
  authenticationRequired,
  invalidRefreshToken,
  loginReturns,
} from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { apiFetch, apiJson } from "./client";
import { ApiError, NetworkError } from "./errors";
import { createBrowserSession, type AuthSession } from "./session";

function deferred() {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => (resolve = done));
  return { promise, resolve };
}

/** A session logged in with `token`. */
async function sessionWith(token: string): Promise<AuthSession> {
  server.use(loginReturns(token));
  const session = createBrowserSession();
  await session.login("coach@example.com", "correct horse");
  return session;
}

/**
 * GET /squad/players: 200 for "Bearer <valid>", 401 for anything else. Counts each attempt by its
 * Authorization header.
 */
function playersAcceptingOnly(valid: string) {
  const attempts: (string | null)[] = [];
  server.use(
    http.get("/squad/players", ({ request }) => {
      const authorization = request.headers.get("Authorization");
      attempts.push(authorization);
      return authorization === `Bearer ${valid}`
        ? HttpResponse.json([{ id: "p1" }])
        : authenticationRequired();
    }),
  );
  return attempts;
}

/** POST /auth/refresh: waits for `gate` (if any), then answers with `answer`. Counts the calls. */
function refreshAnswering(answer: () => Response, gate?: Promise<void>) {
  const calls = { count: 0 };
  server.use(
    http.post("/auth/refresh", async () => {
      calls.count++;
      await gate;
      return answer();
    }),
  );
  return calls;
}

/**
 * A FormData with one "file" part. Built by Node's own Response, not `new FormData()`: in Vitest's
 * jsdom environment FormData and File are jsdom's, but fetch is Node's, which then sends the file
 * as the text "undefined". (Browsers have one implementation, so the app isn't affected.)
 */
function uploadForm(filename: string, content: string): Promise<FormData> {
  const boundary = "squadpulse-test-boundary";
  const multipart =
    `--${boundary}\r\n` +
    `Content-Disposition: form-data; name="file"; filename="${filename}"\r\n` +
    "Content-Type: image/png\r\n\r\n" +
    `${content}\r\n--${boundary}--\r\n`;
  return new Response(multipart, {
    headers: { "Content-Type": `multipart/form-data; boundary=${boundary}` },
  }).formData();
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe("apiFetch", () => {
  it("sends the access token when there is one", async () => {
    const session = await sessionWith("t1");
    const attempts = playersAcceptingOnly("t1");

    const players = await apiJson<{ id: string }[]>("/squad/players", {}, session);

    expect(players).toEqual([{ id: "p1" }]);
    expect(attempts).toEqual(["Bearer t1"]);
  });

  it("sends no Authorization header without a token", async () => {
    const seen: (string | null)[] = [];
    server.use(
      http.post("/auth/forgot-password", ({ request }) => {
        seen.push(request.headers.get("Authorization"));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    const result = await apiJson<void>(
      "/auth/forgot-password",
      { method: "POST", json: { email: "a@b.c" } },
      createBrowserSession(),
    );

    expect(result).toBeUndefined();
    expect(seen).toEqual([null]);
  });

  describe("public endpoints", () => {
    it("never sends the token to /auth/reset-password, so its 401 neither refreshes nor resends the code", async () => {
      const session = await sessionWith("t1");
      const received: { authorization: string | null; body: unknown }[] = [];
      server.use(
        http.post("/auth/reset-password", async ({ request }) => {
          received.push({
            authorization: request.headers.get("Authorization"),
            body: await request.json(),
          });
          return apiError(401, "Unauthorized", "Invalid or expired code");
        }),
      );
      const refresh = refreshAnswering(() => accessToken("t2"));
      const reset = { email: "coach@example.com", code: "123456", newPassword: "correct horse" };

      const error = await apiFetch(
        "/auth/reset-password",
        { method: "POST", json: reset },
        session,
      ).catch((e: unknown) => e);

      expect((error as ApiError).status).toBe(401);
      expect(received).toEqual([{ authorization: null, body: reset }]);
      expect(refresh.count).toBe(0);
      expect(session.getStatus()).toBe("authenticated");
      expect(session.getAccessToken()).toBe("t1");
    });

    it.each(["/auth/forgot-password", "/auth/forgot-password?lang=he"])(
      "never sends the token to %s",
      async (path) => {
        const session = await sessionWith("t1");
        const seen: (string | null)[] = [];
        server.use(
          http.post("/auth/forgot-password", ({ request }) => {
            seen.push(request.headers.get("Authorization"));
            return new HttpResponse(null, { status: 202 });
          }),
        );

        await apiFetch(path, { method: "POST", json: { email: "a@b.c" } }, session);

        expect(seen).toEqual([null]);
      },
    );
  });

  it("builds every URL with apiUrl (VITE_API_BASE_URL)", async () => {
    vi.resetModules();
    vi.stubEnv("VITE_API_BASE_URL", "https://api.example.com/");
    const client = await import("./client");
    const { createBrowserSession: freshSession } = await import("./session");
    server.use(
      http.get("https://api.example.com/clubs/me", () => HttpResponse.json({ name: "Test FC" })),
    );

    expect(await client.apiJson("/clubs/me", {}, freshSession())).toEqual({ name: "Test FC" });
  });

  it("refreshes once for concurrent 401s, then retries each request once", async () => {
    const session = await sessionWith("old");
    const attempts = playersAcceptingOnly("new");
    const gate = deferred();
    const refresh = refreshAnswering(() => accessToken("new"), gate.promise);

    const requests = [1, 2, 3].map(() => apiJson("/squad/players", {}, session));
    // Every request has had its 401 and the one refresh is waiting.
    await vi.waitFor(() => expect(attempts).toHaveLength(3));
    await vi.waitFor(() => expect(refresh.count).toBe(1));
    gate.resolve();

    expect(await Promise.all(requests)).toEqual([[{ id: "p1" }], [{ id: "p1" }], [{ id: "p1" }]]);
    expect(refresh.count).toBe(1);
    expect(attempts.filter((a) => a === "Bearer old")).toHaveLength(3);
    expect(attempts.filter((a) => a === "Bearer new")).toHaveLength(3);
    expect(session.getAccessToken()).toBe("new");
  });

  it("retries only once: a second 401 ends the session", async () => {
    const session = await sessionWith("old");
    const attempts = playersAcceptingOnly("never");
    const refresh = refreshAnswering(() => accessToken("new"));

    const error = await apiFetch("/squad/players", {}, session).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect(refresh.count).toBe(1);
    expect(attempts).toEqual(["Bearer old", "Bearer new"]);
    expect(session.getStatus()).toBe("unauthenticated");
    expect(session.getAccessToken()).toBeNull();
  });

  describe("when another caller renewed the token while the request was in flight", () => {
    /**
     * GET /squad/players: a request with "Bearer A" waits for `gate`, then gets 401; one with
     * "Bearer B" gets `answerForB`. Counts each attempt by its Authorization header.
     */
    function playersWithSlowOldToken(gate: Promise<void>, answerForB: () => Response) {
      const attempts: (string | null)[] = [];
      server.use(
        http.get("/squad/players", async ({ request }) => {
          const authorization = request.headers.get("Authorization");
          attempts.push(authorization);
          if (authorization === "Bearer B") {
            return answerForB();
          }
          await gate;
          return authenticationRequired();
        }),
      );
      return attempts;
    }

    it("retries once with the new token, without a second refresh", async () => {
      const session = await sessionWith("A");
      const gate = deferred();
      const attempts = playersWithSlowOldToken(gate.promise, () =>
        HttpResponse.json([{ id: "p1" }]),
      );
      const refresh = refreshAnswering(() => accessToken("B"));

      const request = apiJson("/squad/players", {}, session);
      await vi.waitFor(() => expect(attempts).toEqual(["Bearer A"]));
      expect(await session.refresh()).toBe("B");
      gate.resolve();

      expect(await request).toEqual([{ id: "p1" }]);
      expect(attempts).toEqual(["Bearer A", "Bearer B"]);
      expect(refresh.count).toBe(1);
    });

    it("counts that retry as the one retry: a 401 to it ends the session, with no refresh", async () => {
      const session = await sessionWith("A");
      const gate = deferred();
      const attempts = playersWithSlowOldToken(gate.promise, authenticationRequired);
      const refresh = refreshAnswering(() => accessToken("B"));

      const request = apiFetch("/squad/players", {}, session).catch((e: unknown) => e);
      await vi.waitFor(() => expect(attempts).toEqual(["Bearer A"]));
      await session.refresh();
      gate.resolve();

      expect(((await request) as ApiError).status).toBe(401);
      expect(attempts).toEqual(["Bearer A", "Bearer B"]);
      expect(refresh.count).toBe(1);
      expect(session.getStatus()).toBe("unauthenticated");
      expect(session.getAccessToken()).toBeNull();
    });
  });

  it("rejects a request waiting on a refresh that the session ended during", async () => {
    const session = await sessionWith("old");
    const attempts = playersAcceptingOnly("new");
    const gate = deferred();
    const refresh = refreshAnswering(() => accessToken("new"), gate.promise);

    const request = apiFetch("/squad/players", {}, session).catch((e: unknown) => e);
    await vi.waitFor(() => expect(refresh.count).toBe(1));
    session.clear();
    gate.resolve();

    expect(((await request) as ApiError).status).toBe(401);
    // Never resent with the late token.
    expect(attempts).toEqual(["Bearer old"]);
    expect(session.getStatus()).toBe("unauthenticated");
    expect(session.getAccessToken()).toBeNull();
  });

  it("doesn't revive a session ended while the request was in flight", async () => {
    const session = await sessionWith("A");
    const gate = deferred();
    const attempts: (string | null)[] = [];
    server.use(
      http.get("/squad/players", async ({ request }) => {
        attempts.push(request.headers.get("Authorization"));
        await gate.promise;
        return authenticationRequired();
      }),
    );
    // A refresh would still succeed (the cookie is valid), so it must not be sent at all.
    const refresh = refreshAnswering(() => accessToken("B"));

    const request = apiFetch("/squad/players", {}, session).catch((e: unknown) => e);
    await vi.waitFor(() => expect(attempts).toEqual(["Bearer A"]));
    session.clear();
    gate.resolve();

    expect(((await request) as ApiError).status).toBe(401);
    expect(attempts).toEqual(["Bearer A"]);
    expect(refresh.count).toBe(0);
    expect(session.getStatus()).toBe("unauthenticated");
    expect(session.getAccessToken()).toBeNull();
  });

  it("ends the session and rejects every waiting request when the refresh is refused", async () => {
    const session = await sessionWith("old");
    playersAcceptingOnly("new");
    const gate = deferred();
    const refresh = refreshAnswering(invalidRefreshToken, gate.promise);

    const requests = [1, 2, 3].map(() =>
      apiFetch("/squad/players", {}, session).catch((e: unknown) => e),
    );
    await vi.waitFor(() => expect(refresh.count).toBe(1));
    gate.resolve();

    for (const error of await Promise.all(requests)) {
      expect(error).toBeInstanceOf(ApiError);
      expect((error as ApiError).status).toBe(401);
    }
    expect(refresh.count).toBe(1);
    expect(session.getStatus()).toBe("unauthenticated");
    expect(session.getAccessToken()).toBeNull();
  });

  it.each([
    ["GET /auth/users/me", "GET", "/auth/users/me", undefined],
    ["PATCH /clubs/me", "PATCH", "/clubs/me", { name: "New FC" }],
  ])(
    "ends the session when a user who can no longer act gets a 401 from %s",
    async (_name, method, path, json) => {
      const session = await sessionWith("still-valid");
      const answer = () => authenticationRequired();
      server.use(method === "GET" ? http.get(path, answer) : http.patch(path, answer));
      const refresh = refreshAnswering(invalidRefreshToken);

      const error = await apiFetch(path, { method, json }, session).catch((e: unknown) => e);

      expect((error as ApiError).status).toBe(401);
      expect(refresh.count).toBe(1);
      expect(session.getStatus()).toBe("unauthenticated");
    },
  );

  it("doesn't refresh on a 401 from /auth/login (no token sent)", async () => {
    const session = createBrowserSession();
    server.use(
      http.post("/auth/login", () => apiError(401, "Unauthorized", "Invalid email or password")),
    );
    // No /auth/refresh handler: a refresh would fail the test as an unhandled request.

    const error = await session.login("coach@example.com", "wrong").catch((e: unknown) => e);

    expect((error as ApiError).status).toBe(401);
    expect((error as ApiError).message).toBe("Invalid email or password");
    expect(session.getStatus()).toBe("unknown");
  });

  it("doesn't refresh on a 401 to a request sent without a token", async () => {
    const attempts = playersAcceptingOnly("t1");

    const error = await apiFetch("/squad/players", {}, createBrowserSession()).catch(
      (e: unknown) => e,
    );

    expect((error as ApiError).status).toBe(401);
    expect(attempts).toEqual([null]);
  });

  it.each([403, 404, 409, 500])("doesn't refresh on a %i", async (status) => {
    const session = await sessionWith("t1");
    server.use(http.get("/squad/players", () => apiError(status, "Error", "No")));

    const error = await apiFetch("/squad/players", {}, session).catch((e: unknown) => e);

    expect((error as ApiError).status).toBe(status);
    expect(session.getStatus()).toBe("authenticated");
  });

  it("resends a JSON write with the same body after a refresh", async () => {
    const session = await sessionWith("old");
    refreshAnswering(() => accessToken("new"));
    const received: { authorization: string | null; body: unknown; type: string | null }[] = [];
    server.use(
      http.post("/squad/players", async ({ request }) => {
        const authorization = request.headers.get("Authorization");
        received.push({
          authorization,
          body: await request.json(),
          type: request.headers.get("Content-Type"),
        });
        return authorization === "Bearer new"
          ? HttpResponse.json({ id: "p9" }, { status: 201 })
          : authenticationRequired();
      }),
    );

    const player = { fullName: "Dani Levi", primaryPosition: "CB" };
    const created = await apiJson("/squad/players", { method: "POST", json: player }, session);

    expect(created).toEqual({ id: "p9" });
    expect(received).toEqual([
      { authorization: "Bearer old", body: player, type: "application/json" },
      { authorization: "Bearer new", body: player, type: "application/json" },
    ]);
  });

  it("resends a FormData upload with the same file after a refresh", async () => {
    const session = await sessionWith("old");
    refreshAnswering(() => accessToken("new"));
    const received: { authorization: string | null; name: string; content: string }[] = [];
    server.use(
      http.put("/squad/players/p1/photo", async ({ request }) => {
        const authorization = request.headers.get("Authorization");
        const file = (await request.formData()).get("file") as File;
        received.push({ authorization, name: file.name, content: await file.text() });
        return authorization === "Bearer new"
          ? new HttpResponse(null, { status: 204 })
          : authenticationRequired();
      }),
    );

    await apiFetch(
      "/squad/players/p1/photo",
      { method: "PUT", formData: await uploadForm("photo.png", "PNG-bytes") },
      session,
    );

    expect(received).toEqual([
      { authorization: "Bearer old", name: "photo.png", content: "PNG-bytes" },
      { authorization: "Bearer new", name: "photo.png", content: "PNG-bytes" },
    ]);
  });

  it("doesn't cancel the shared refresh when one waiting caller aborts", async () => {
    const session = await sessionWith("old");
    const attempts = playersAcceptingOnly("new");
    const gate = deferred();
    const refresh = refreshAnswering(() => accessToken("new"), gate.promise);
    const controller = new AbortController();

    const aborted = apiFetch("/squad/players", { signal: controller.signal }, session).catch(
      (e: unknown) => e,
    );
    const other = apiJson("/squad/players", {}, session);
    await vi.waitFor(() => expect(attempts).toHaveLength(2));
    await vi.waitFor(() => expect(refresh.count).toBe(1));
    controller.abort();
    gate.resolve();

    expect(await other).toEqual([{ id: "p1" }]);
    expect((await aborted) as Error).toHaveProperty("name", "AbortError");
    expect(refresh.count).toBe(1);
    expect(session.getAccessToken()).toBe("new");
  });

  it.each([
    ["a 502 (dev proxy, backend down)", () => new HttpResponse(null, { status: 502 }), ApiError],
    ["a network failure", () => HttpResponse.error(), NetworkError],
  ])("keeps the session when the refresh gets %s", async (_name, answer, errorType) => {
    const session = await sessionWith("old");
    playersAcceptingOnly("new");
    refreshAnswering(answer);

    const error = await apiFetch("/squad/players", {}, session).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(errorType);
    expect(session.getStatus()).toBe("authenticated");
    expect(session.getAccessToken()).toBe("old");
  });

  it("turns a failure without a response into a NetworkError", async () => {
    server.use(http.get("/squad/players", () => HttpResponse.error()));

    const error = await apiFetch("/squad/players", {}, createBrowserSession()).catch(
      (e: unknown) => e,
    );

    expect(error).toBeInstanceOf(NetworkError);
  });
});
