import type { RequestHandler } from "msw";

/**
 * Default request handlers, active in every test. Deliberately empty: a default login or refresh
 * answer would hide a request a test didn't expect, which onUnhandledRequest: "error" otherwise
 * fails. A test adds what it needs with server.use(...); the shared building blocks for the
 * backend's auth answers are in ./auth.ts.
 */
export const handlers: RequestHandler[] = [];
