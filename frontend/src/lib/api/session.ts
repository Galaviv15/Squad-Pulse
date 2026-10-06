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
  /**
   * POST /auth/login. Stores the token; a failure rejects with ApiError / NetworkError. A refresh
   * running in this tab is first made stale (its callers get a 401 and its token is never stored)
   * and waited for, so neither its token nor its cookie can land after the login's: it may be
   * another user's session.
   */
  login(email: string, password: string): Promise<void>;
  /**
   * Renews the access token: single-flight in this tab and serialized across tabs. Resolves to the
   * new token. A 4xx answer ends the session and rejects with that ApiError; a 5xx or a network
   * failure rejects but keeps the session, since it says nothing about the refresh token. If the
   * session is ended (clear) while a refresh runs, its token is dropped and it rejects with a 401.
   */
  refresh(): Promise<string>;
  /** POST /auth/logout, then ends the session whatever that call returned, even a network failure. */
  logout(): Promise<void>;
  /** Ends the session locally: drops the token and sets the status to "unauthenticated". */
  clear(): void;
}

/** The Web Locks name that serializes refreshes across this origin's tabs. */
export const REFRESH_LOCK_NAME = "squadpulse:auth-refresh";

/** The BroadcastChannel name on which a tab tells the others it logged out. */
export const LOGOUT_CHANNEL_NAME = "squadpulse:auth-logout";

/** The one message sent on LOGOUT_CHANNEL_NAME. */
export const LOGOUT_MESSAGE = "logout";

/** The part of BroadcastChannel the session uses, so tests can pass a fake. */
export interface LogoutChannel {
  postMessage(message: unknown): void;
  onmessage: ((event: MessageEvent) => void) | null;
  close(): void;
}

export interface BrowserSessionOptions {
  /**
   * The channel for cross-tab logout (openLogoutChannel() for the real one). None by default: no
   * cross-tab logout.
   */
  logoutChannel?: LogoutChannel | null;
}

/** The browser session, plus what only its owner needs. */
export interface BrowserSession extends AuthSession {
  /** Closes the logout channel. The session itself keeps working, without cross-tab logout. */
  dispose(): void;
}

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
 *
 * Cross-tab logout: an explicit logout() posts LOGOUT_MESSAGE on the logout channel once it has
 * run, and every other tab that receives it ends its own session with clear(), never logout():
 * the first tab has already revoked the family and cleared the shared cookie, so a second POST
 * would only repeat that. A session that ends by itself (a refused refresh) isn't broadcast, and a
 * login isn't either.
 */
export function createBrowserSession(options: BrowserSessionOptions = {}): BrowserSession {
  const store = createStore<SessionState>()(() => ({ accessToken: null, status: "unknown" }));
  let inFlightRefresh: Promise<string> | null = null;
  // Bumped whenever the session ends or a login starts, so a refresh that started before can tell.
  let generation = 0;

  const authenticate = (accessToken: string) =>
    store.setState({ accessToken, status: "authenticated" });
  const clear = () => {
    generation++;
    store.setState({ accessToken: null, status: "unauthenticated" });
  };

  const logoutChannel = options.logoutChannel ?? null;
  if (logoutChannel) {
    logoutChannel.onmessage = (event) => {
      if (event.data === LOGOUT_MESSAGE) {
        clear();
      }
    };
  }

  async function refreshOnce(): Promise<string> {
    const startedIn = generation;
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
    // The session ended (e.g. another request's retry got 401) or a login started while this
    // refresh was running: a late token must not bring the old session back. Its callers get a
    // 401 instead.
    if (generation !== startedIn) {
      throw new ApiError(401, null);
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
      // Bump first: once the refresh has settled it would already have stored its token. Waiting
      // also lets its Set-Cookie land before the login's. Only this tab's refresh is covered: a
      // refresh in another tab racing this login isn't (no cross-tab lock here), accepted.
      generation++;
      await inFlightRefresh?.catch(() => undefined);
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
      try {
        logoutChannel?.postMessage(LOGOUT_MESSAGE);
      } catch {
        // A closed channel: the other tabs keep their sessions until their tokens expire.
      }
    },

    clear,

    dispose() {
      if (logoutChannel) {
        logoutChannel.onmessage = null;
        logoutChannel.close();
      }
    },
  };
}

/** A BroadcastChannel on LOGOUT_CHANNEL_NAME, or null where the browser has none. */
export function openLogoutChannel(): LogoutChannel | null {
  return typeof BroadcastChannel === "function" ? new BroadcastChannel(LOGOUT_CHANNEL_NAME) : null;
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

// Created on first use, not at import: tests install their own before anything uses it, so the
// test run never opens a real BroadcastChannel (Vitest's jsdom environment has Node's).
let appSession: BrowserSession | null = null;
const app = () => (appSession ??= createBrowserSession({ logoutChannel: openLogoutChannel() }));

/**
 * The app's session, with cross-tab logout. A fixed object that forwards to the current browser
 * session, so the test seam (resetAuthSessionForTests) can swap that session underneath.
 */
export const authSession: AuthSession = {
  getAccessToken: () => app().getAccessToken(),
  getStatus: () => app().getStatus(),
  subscribe: (listener) => app().subscribe(listener),
  login: (email, password) => app().login(email, password),
  refresh: () => app().refresh(),
  logout: () => app().logout(),
  clear: () => app().clear(),
};

/**
 * Tests only (src/test/setup.ts runs it before and after each test): replaces the app's session
 * with a fresh one (status "unknown", no token, nothing in flight, no subscribers) and closes the
 * old one's logout channel. The new one has no logout channel unless a test passes one (a fake).
 */
export function resetAuthSessionForTests(options: BrowserSessionOptions = {}) {
  appSession?.dispose();
  appSession = createBrowserSession(options);
}

/** The session's status, re-rendering on every change. The route guards route on it. */
export function useSessionStatus(session: AuthSession = authSession): SessionStatus {
  return useSyncExternalStore(session.subscribe, session.getStatus);
}
