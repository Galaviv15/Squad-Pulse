import type { RequestHandler } from "msw";

/**
 * Default request handlers, active in every test. Empty for now: the API client (KAN-46) adds
 * the shared ones. A test that needs a specific response adds it with server.use(...).
 */
export const handlers: RequestHandler[] = [];
