import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach } from "vitest";
// Adds jest-dom matchers (toBeInTheDocument, ...) to Vitest's expect.
import "@testing-library/jest-dom/vitest";
import { resetAuthSessionForTests } from "@/lib/api/session";
import { resetSessionBootstrap } from "@/lib/auth/bootstrap";
import { server } from "./msw/server";

// A request no handler covers fails the test instead of reaching the network.
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
});

// The app session and the app-load bootstrap are module-level. Every test starts with a fresh
// session ("unknown", no token, no subscribers, no logout channel) and no bootstrap; installed
// before the test too, so the app session's real BroadcastChannel is never opened.
beforeEach(() => {
  resetAuthSessionForTests();
  resetSessionBootstrap();
});

afterEach(() => {
  // Testing Library unmounts after each test by itself only when Vitest's globals are on; they
  // aren't here, so do it explicitly.
  cleanup();
  // Close the session's (fake) channel and forget anything a test left in flight.
  resetAuthSessionForTests();
  resetSessionBootstrap();
  // Drop the handlers a test added with server.use(...).
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});
