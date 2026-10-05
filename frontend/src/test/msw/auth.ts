import { http, HttpResponse } from "msw";

/**
 * Building blocks for the backend's auth answers, for tests to put together with server.use(...).
 * None is a default handler: a test that didn't expect a login or refresh should fail on it (an
 * unhandled request), not silently get a token.
 */

/** An error in the backend's ApiErrorResponse shape. */
export function apiError(status: number, error: string, message: string, details: string[] = []) {
  return HttpResponse.json(
    { timestamp: "2026-01-01T00:00:00Z", status, error, message, details },
    { status },
  );
}

/** The 401 for a missing, invalid or expired access token, or an ActiveCallerCheck failure. */
export const authenticationRequired = () =>
  apiError(401, "Unauthorized", "Authentication required");

/** The 401 /auth/refresh answers for a missing, revoked or reused refresh token. */
export const invalidRefreshToken = () =>
  apiError(401, "Unauthorized", "Invalid or expired refresh token");

/** A successful /auth/login or /auth/refresh body (AccessTokenResponse). */
export const accessToken = (token: string) =>
  HttpResponse.json({ accessToken: token, tokenType: "Bearer", expiresIn: 900 });

/** POST /auth/login answering with `token`. */
export const loginReturns = (token: string) => http.post("/auth/login", () => accessToken(token));
