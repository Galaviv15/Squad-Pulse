import { useSyncExternalStore } from "react";
import { createStore } from "zustand/vanilla";
import { ApiError, apiErrorFrom } from "./errors";
import { send } from "./transport";

/**
 * "unknown" until something has decided (at app load, before the first refresh / login),
 * "authenticated" while there's an access token, "unauthenticated" once the session has ended.
 */
export type SessionStatus = "unknown" | "authenticated" | "unauthenticated";

/**
 * The session: where the access token lives and how it's renewed. The only thing the request
 * layer, hooks and screens know about; they never touch the store or cookies themselves. Kept
 * behind this interface so a desktop build can swap token storage and refresh transport; only the
 * browser implementation exists.
 */
export interface AuthSession {
  /** The current access token, or null. */
  getAccessToken(): string | null;
  getStatus(): SessionStatus;
  /** Calls `listener` after every status change; returns the function that unsubscribes. */
  subscribe(listener: (status: SessionStatus) => void): () => void;
  /** POST /auth/login. Stores the token; a failure rejects with ApiError / NetworkError. */
  login(email: string, password: string): Promise<void>;
  /**
   * Renews the access token: single-flight in this tab and serialized across tabs. Resolves to the
   * new token. A 4xx answer ends the session and rejects with that ApiError; a 5xx or a network
   * failure rejects but keeps the session, since it says nothing about the refresh token.
   */
  refresh(): Promise<string>;
  /** POST /auth/logout, then ends the session whatever that call returned, even a network failure. */
  logout(): Promise<void>;
  /** Ends the session locally: drops the token and sets the status to "unauthenticated". */
  clear(): void;
}

/** The Web Locks name that serializes refreshes across this origin's tabs. */
export const REFRESH_LOCK_NAME = "squadpulse:auth-refresh";

interface SessionState {
  accessToken: string | null;
  status: SessionStatus;
}

/**
 * The browser session. The access token is in memory only: a plain Zustand store, with no persist
 * middleware, so nothing reaches localStorage, sessionStorage, IndexedDB or a script-readable
 * cookie. The refresh token is the backend's HttpOnly cookie (Path=/auth), sent by the browser on
 * the /auth calls below and never seen by this code.
 *
 * Refresh rules, all driven by the backend's reuse detection: every refresh rotates the cookie's
 * token, and presenting an already-rotated token revokes the whole token family, i.e. logs the
 * user out. So two refreshes must never be sent with the same cookie:
 * - in this tab, every caller shares one in-flight refresh (`inFlightRefresh`, in this closure,
 *   never in React state, so StrictMode's double effects can't start a second one);
 * - across tabs, the POST runs inside a Web Lock: a tab that waited for the lock finds the browser
 *   already holding the rotated cookie, so its own refresh is a normal rotation, not a reuse.
 *   Without navigator.locks (no secure context, e.g. the dev server opened by a LAN IP; very old
 *   browsers; jsdom) only the in-tab rule applies.
 * - a refresh is never aborted: the server may already have rotated the token, and a browser that
 *   never stores the new cookie presents the old one next time, which counts as reuse. Accepted
 *   residual risk: closing or reloading the page mid-refresh can still do that and end the
 *   session; the real fix would be a server-side grace window, not something the client can do.
 */
export function createBrowserSession(): AuthSession {
  const store = createStore<SessionState>()(() => ({ accessToken: null, status: "unknown" }));
  let inFlightRefresh: Promise<string> | null = null;

  const authenticate = (accessToken: string) =>
    store.setState({ accessToken, status: "authenticated" });
  const clear = () => store.setState({ accessToken: null, status: "unauthenticated" });

  async function refreshOnce(): Promise<string> {
    let token: string;
    try {
      token = await withRefreshLock(() => postForToken("/auth/refresh"));
    } catch (error) {
      // Only an answer from the backend that rejects the cookie ends the session. A 5xx (the dev
      // proxy's 502 for a stopped backend, a 504 from a reverse proxy) or no response at all
      // leaves it: the next request's 401 simply tries again, and if the cookie really is dead
      // that attempt gets the 401 that ends it.
      if (error instanceof ApiError && error.status >= 400 && error.status <= 499) {
        clear();
      }
      throw error;
    }
    authenticate(token);
    return token;
  }

  return {
    getAccessToken: () => store.getState().accessToken,
    getStatus: () => store.getState().status,
    subscribe: (listener) =>
      store.subscribe((state, previous) => {
        if (state.status !== previous.status) {
          listener(state.status);
        }
      }),

    async login(email, password) {
      authenticate(
        await postForToken("/auth/login", {
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ email, password }),
        }),
      );
    },

    refresh() {
      inFlightRefresh ??= refreshOnce().finally(() => {
        inFlightRefresh = null;
      });
      return inFlightRefresh;
    },

    async logout() {
      // Let a running refresh finish first, so the cookie sent is the family's current one.
      await inFlightRefresh?.catch(() => undefined);
      try {
        await send("/auth/logout", { method: "POST" });
      } catch {
        // Logged out locally anyway; the server-side session expires on its own.
      } finally {
        clear();
      }
    },

    clear,
  };
}

/** POST to /auth/login or /auth/refresh, without an access token, and read the new one. */
async function postForToken(path: string, init: RequestInit = {}): Promise<string> {
  const response = await send(path, { ...init, method: "POST" });
  if (!response.ok) {
    throw await apiErrorFrom(response);
  }
  const body: unknown = await response.json();
  const accessToken = (body as { accessToken?: unknown } | null)?.accessToken;
  if (typeof accessToken !== "string" || accessToken === "") {
    throw new Error("The token response has no access token");
  }
  return accessToken;
}

/** Runs `task` holding the cross-tab refresh lock, or directly where Web Locks don't exist. */
async function withRefreshLock<T>(task: () => Promise<T>): Promise<T> {
  // Typed as always present, but only exists in secure contexts.
  const locks = (navigator as Partial<NavigatorLocks>).locks;
  return locks ? await locks.request(REFRESH_LOCK_NAME, task) : await task();
}

/** The app's session. */
export const authSession: AuthSession = createBrowserSession();

/** The session's status, re-rendering on every change. KAN-47 routes on it. */
export function useSessionStatus(session: AuthSession = authSession): SessionStatus {
  return useSyncExternalStore(session.subscribe, session.getStatus);
}
