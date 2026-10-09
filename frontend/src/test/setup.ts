import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach } from "vitest";
// Adds jest-dom matchers (toBeInTheDocument, ...) to Vitest's expect.
import "@testing-library/jest-dom/vitest";
import { resetAuthSessionForTests } from "@/lib/api/session";
import { resetSessionBootstrap } from "@/lib/auth/bootstrap";
import {
  beginConsoleGuardTest,
  endConsoleGuardFile,
  endConsoleGuardTest,
  installConsoleGuard,
} from "./consoleGuard";
import { server } from "./msw/server";

// An unexpected console.error / console.warn fails the test (see consoleGuard.ts). Installed
// before anything else logs; not a vi.spyOn, so vi.restoreAllMocks() leaves it in place.
installConsoleGuard();

// A request no handler covers fails the test instead of reaching the network.
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
});

// The app session and the app-load bootstrap are module-level. Every test starts with a fresh
// session ("unknown", no token, no subscribers, no logout channel) and no bootstrap; installed
// before the test too, so the app session's real BroadcastChannel is never opened.
beforeEach(() => {
  beginConsoleGuardTest();
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
  // Last, so a warning from the cleanup above counts too. The setup file's afterEach runs after
  // the test file's own (Vitest's default sequence.hooks "stack").
  const unexpected = endConsoleGuardTest();
  if (unexpected) throw unexpected;
});

afterAll(() => {
  server.close();
  const unexpected = endConsoleGuardFile();
  if (unexpected) throw unexpected;
});
