import { apiErrorFrom } from "./errors";
import { authSession, type AuthSession } from "./session";
import { send } from "./transport";

/**
 * The backend's public endpoints, which never get the access token. Must stay in sync with
 * SecurityConfig.PUBLIC_ENDPOINTS. Without a token their 401s (a wrong password or reset code)
 * never refresh, so a reset code is never sent twice.
 */
const PUBLIC_ENDPOINTS: ReadonlySet<string> = new Set([
  "/auth/login",
  "/auth/refresh",
  "/auth/logout",
  "/auth/forgot-password",
  "/auth/reset-password",
]);

/** Whether `path` is a public endpoint: the path alone, without a query string, exactly. */
function isPublicEndpoint(path: string): boolean {
  return PUBLIC_ENDPOINTS.has(path.split("?", 1)[0]);
}

export interface ApiRequestOptions {
  /** Defaults to GET. */
  method?: string;
  /** A JSON body, serialized (and Content-Type set) on every attempt. */
  json?: unknown;
  /** A multipart body (uploads). A FormData can be sent again as is, so the retry reuses it. */
  formData?: FormData;
  headers?: Record<string, string>;
  /** Aborts this request (TanStack Query passes one); never the shared refresh it may wait for. */
  signal?: AbortSignal;
}

/**
 * Sends a request to the backend. Everything that calls the backend goes through this (or
 * apiJson); only the session's own /auth/login, /auth/refresh and /auth/logout calls don't. The
 * access token, if there is one, goes in the Authorization header, except to a public endpoint
 * (PUBLIC_ENDPOINTS), which never gets it. Resolves to the 2xx response; any other status rejects
 * with an ApiError, no response with a NetworkError.
 *
 * Refresh-and-retry: a request that was sent with a token and got 401 renews the token once (the
 * session's shared refresh) and is sent once more, rebuilt from `options`. A 401 sent without a
 * token (a public endpoint, any request before login) is just an error; 403 and every other
 * status never refresh. A second 401 ends the session. A request whose session was ended while it
 * was in flight (no token any more) just rejects with its 401: refreshing would revive a session
 * ended on purpose. A failed refresh rejects with the refresh's error (a 4xx has already ended the
 * session, see AuthSession.refresh).
 *
 * Sending a write twice is safe: a 401 is decided before any handler changes anything, either by
 * the security filter or by ActiveCallerCheck, the first step of every write that re-checks its
 * caller (invite, permission level, deactivate / reactivate, PATCH /clubs/me).
 */
export async function apiFetch(
  path: string,
  options: ApiRequestOptions = {},
  session: AuthSession = authSession,
): Promise<Response> {
  const sentToken = isPublicEndpoint(path) ? null : session.getAccessToken();
  const response = await send(path, buildInit(options, sentToken));
  if (response.status !== 401 || sentToken === null) {
    return ensureOk(response);
  }

  // The session ended while this request was in flight: don't bring it back.
  const current = session.getAccessToken();
  if (current === null) {
    return ensureOk(response);
  }
  // Another caller may already have renewed the token since this one was sent: use that instead
  // of refreshing again.
  const token = current !== sentToken ? current : await session.refresh();

  const retried = await send(path, buildInit(options, token));
  if (retried.status === 401) {
    session.clear();
  }
  return ensureOk(retried);
}

/**
 * apiFetch, then the JSON body. Resolves to undefined for a 204 or an empty body, so a call to an
 * endpoint without one is typed apiJson<void>(...).
 */
export async function apiJson<T>(
  path: string,
  options: ApiRequestOptions = {},
  session: AuthSession = authSession,
): Promise<T> {
  const response = await apiFetch(path, options, session);
  const text = response.status === 204 ? "" : await response.text();
  return (text === "" ? undefined : JSON.parse(text)) as T;
}

/** A fresh RequestInit for one attempt, so a retry never reuses a consumed Request or body. */
function buildInit(options: ApiRequestOptions, token: string | null): RequestInit {
  const headers: Record<string, string> = { ...options.headers };
  let body: BodyInit | undefined;
  if (options.json !== undefined) {
    headers["Content-Type"] = "application/json";
    body = JSON.stringify(options.json);
  } else if (options.formData !== undefined) {
    // No Content-Type: fetch sets multipart/form-data with the boundary.
    body = options.formData;
  }
  if (token !== null) {
    headers.Authorization = `Bearer ${token}`;
  }
  return { method: options.method ?? "GET", headers, body, signal: options.signal };
}

async function ensureOk(response: Response): Promise<Response> {
  if (!response.ok) {
    throw await apiErrorFrom(response);
  }
  return response;
}
