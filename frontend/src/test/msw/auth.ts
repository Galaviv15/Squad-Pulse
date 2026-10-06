import { http, HttpResponse } from "msw";
import type { CurrentUser } from "@/lib/auth/currentUser";

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

/** POST /auth/refresh answering with `token`. */
export const refreshReturns = (token: string) =>
  http.post("/auth/refresh", () => accessToken(token));

/** POST /auth/refresh refusing the cookie: a first visit, or a session that has ended. */
export const refreshRefused = () => http.post("/auth/refresh", () => invalidRefreshToken());

/** A GET /auth/users/me body (CurrentUserResponse). */
export function currentUserBody(overrides: Partial<CurrentUser> = {}): CurrentUser {
  return {
    id: "u1",
    email: "coach@example.com",
    fullName: "Dana Cohen",
    title: "HEAD_COACH",
    permissionLevel: "ADMIN",
    dateOfBirth: "1985-04-12",
    active: true,
    hasPhoto: false,
    activated: true,
    club: { id: "c1", name: "Test FC", hasLogo: false },
    ...overrides,
  };
}

/** GET /auth/users/me answering with currentUserBody(overrides). */
export const meReturns = (overrides: Partial<CurrentUser> = {}) =>
  http.get("/auth/users/me", () => HttpResponse.json(currentUserBody(overrides)));

/** An app load with a valid refresh cookie: the refresh succeeds and /me answers. */
export const loggedIn = () => [refreshReturns("t1"), meReturns()];
