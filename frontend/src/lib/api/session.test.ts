import { act, renderHook } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { accessToken, apiError, loginReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import {
  createBrowserSession,
  REFRESH_LOCK_NAME,
  useSessionStatus,
  type SessionStatus,
} from "./session";

/** POST /auth/refresh answering with successive tokens ("r1", "r2", ...). Counts the calls. */
function refreshRotating(onCall: () => void = () => {}) {
  const calls = { count: 0 };
  server.use(
    http.post("/auth/refresh", () => {
      calls.count++;
      onCall();
      return accessToken(`r${calls.count}`);
    }),
  );
  return calls;
}

/** POST /auth/refresh answering "late" once `release` is called. Counts the calls. */
function refreshWaiting() {
  const calls = { count: 0 };
  let release!: () => void;
  const gate = new Promise<void>((resolve) => (release = resolve));
  server.use(
    http.post("/auth/refresh", async () => {
      calls.count++;
      await gate;
      return accessToken("late");
    }),
  );
  return { calls, release };
}

afterEach(() => {
  // Drop a fake navigator.locks a test installed; jsdom has none of its own.
  Reflect.deleteProperty(navigator, "locks");
  vi.restoreAllMocks();
});

describe("createBrowserSession", () => {
  it("starts unknown, and is authenticated after login", async () => {
    server.use(loginReturns("t1"));
    const session = createBrowserSession();
    const statuses: SessionStatus[] = [];
    session.subscribe((status) => statuses.push(status));

    expect(session.getStatus()).toBe("unknown");
    await session.login("coach@example.com", "correct horse");

    expect(session.getAccessToken()).toBe("t1");
    expect(statuses).toEqual(["authenticated"]);
  });

  it("sends the login as JSON, without an access token", async () => {
    const seen: unknown[] = [];
    server.use(
      http.post("/auth/login", async ({ request }) => {
        seen.push(request.headers.get("Authorization"), await request.json());
        return accessToken("t1");
      }),
    );

    await createBrowserSession().login("coach@example.com", "pw");

    expect(seen).toEqual([null, { email: "coach@example.com", password: "pw" }]);
  });

  it("writes nothing to localStorage or sessionStorage on login and refresh", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    server.use(loginReturns("t1"));
    refreshRotating();
    const session = createBrowserSession();

    await session.login("coach@example.com", "pw");
    await session.refresh();

    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    expect(document.cookie).toBe("");
  });

  it("refreshes once for concurrent calls without navigator.locks", async () => {
    expect("locks" in navigator).toBe(false);
    const refresh = refreshRotating();
    const session = createBrowserSession();

    const tokens = await Promise.all([session.refresh(), session.refresh(), session.refresh()]);

    expect(tokens).toEqual(["r1", "r1", "r1"]);
    expect(refresh.count).toBe(1);
    expect(session.getStatus()).toBe("authenticated");
    // A later refresh is a new one.
    expect(await session.refresh()).toBe("r2");
  });

  it("sends the refresh while holding the cross-tab lock", async () => {
    let held = false;
    const request = vi.fn(async (_name: string, callback: (lock: Lock | null) => unknown) => {
      held = true;
      try {
        return await callback({ name: REFRESH_LOCK_NAME, mode: "exclusive" } as Lock);
      } finally {
        held = false;
      }
    });
    Object.defineProperty(navigator, "locks", { value: { request }, configurable: true });
    const heldDuringPost: boolean[] = [];
    refreshRotating(() => heldDuringPost.push(held));
    const session = createBrowserSession();

    const tokens = await Promise.all([session.refresh(), session.refresh()]);

    expect(tokens).toEqual(["r1", "r1"]);
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith(REFRESH_LOCK_NAME, expect.any(Function));
    expect(heldDuringPost).toEqual([true]);
  });

  it("ends the session when the refresh is refused", async () => {
    server.use(
      http.post("/auth/refresh", () =>
        apiError(401, "Unauthorized", "Invalid or expired refresh token"),
      ),
    );
    const session = createBrowserSession();

    await expect(session.refresh()).rejects.toHaveProperty("status", 401);
    expect(session.getStatus()).toBe("unauthenticated");
  });

  describe("logout", () => {
    it.each([
      ["answers 204", () => new HttpResponse(null, { status: 204 })],
      ["fails with a 500", () => new HttpResponse("<html>oops</html>", { status: 500 })],
      ["can't be reached", () => HttpResponse.error()],
    ])("ends the session when /auth/logout %s", async (_name, answer) => {
      server.use(loginReturns("t1"));
      const calls: (string | null)[] = [];
      server.use(
        http.post("/auth/logout", ({ request }) => {
          calls.push(request.headers.get("Authorization"));
          return answer();
        }),
      );
      const session = createBrowserSession();
      await session.login("coach@example.com", "pw");

      await session.logout();

      expect(calls).toEqual([null]);
      expect(session.getStatus()).toBe("unauthenticated");
      expect(session.getAccessToken()).toBeNull();
    });
  });

  describe("a refresh still running when the session ends", () => {
    it("doesn't bring back a session cleared in the meantime", async () => {
      server.use(loginReturns("t1"));
      const refresh = refreshWaiting();
      const session = createBrowserSession();
      await session.login("coach@example.com", "pw");

      const refreshed = session.refresh().catch((e: unknown) => e);
      await vi.waitFor(() => expect(refresh.calls.count).toBe(1));
      session.clear();
      refresh.release();

      expect(await refreshed).toHaveProperty("status", 401);
      expect(session.getStatus()).toBe("unauthenticated");
      expect(session.getAccessToken()).toBeNull();
    });

    it("lets logout wait for it, then ends the session", async () => {
      server.use(loginReturns("t1"));
      server.use(http.post("/auth/logout", () => new HttpResponse(null, { status: 204 })));
      const refresh = refreshWaiting();
      const session = createBrowserSession();
      await session.login("coach@example.com", "pw");

      const refreshed = session.refresh();
      await vi.waitFor(() => expect(refresh.calls.count).toBe(1));
      const loggedOut = session.logout();
      refresh.release();
      await loggedOut;

      expect(await refreshed).toBe("late");
      expect(session.getStatus()).toBe("unauthenticated");
      expect(session.getAccessToken()).toBeNull();
    });
  });

  describe("subscribe", () => {
    it("reports status changes only, not a token renewed by a refresh", async () => {
      server.use(loginReturns("t1"));
      refreshRotating();
      const session = createBrowserSession();
      const statuses: SessionStatus[] = [];
      session.subscribe((status) => statuses.push(status));

      await session.login("coach@example.com", "pw");
      await session.refresh();
      expect(session.getAccessToken()).toBe("r1");
      session.clear();

      expect(statuses).toEqual(["authenticated", "unauthenticated"]);
    });

    it("stops reporting once unsubscribed", async () => {
      server.use(loginReturns("t1"));
      const session = createBrowserSession();
      const statuses: SessionStatus[] = [];
      const unsubscribe = session.subscribe((status) => statuses.push(status));

      await session.login("coach@example.com", "pw");
      unsubscribe();
      session.clear();

      expect(statuses).toEqual(["authenticated"]);
    });
  });

  it("useSessionStatus re-renders on a status change", async () => {
    server.use(loginReturns("t1"));
    const session = createBrowserSession();
    const { result } = renderHook(() => useSessionStatus(session));
    expect(result.current).toBe("unknown");

    await act(() => session.login("coach@example.com", "pw"));
    expect(result.current).toBe("authenticated");

    act(() => session.clear());
    expect(result.current).toBe("unauthenticated");
  });

  it("clear() ends the session", async () => {
    server.use(loginReturns("t1"));
    const session = createBrowserSession();
    await session.login("coach@example.com", "pw");

    session.clear();

    expect(session.getStatus()).toBe("unauthenticated");
    expect(session.getAccessToken()).toBeNull();
  });
});
