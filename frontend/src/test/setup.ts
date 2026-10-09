import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll, beforeEach, expect } from "vitest";
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
import {
  beginUnhandledRequestGuardTest,
  blockUnhandledRequest,
  endUnhandledRequestGuardFile,
  endUnhandledRequestGuardTest,
  installUnhandledRequestGuard,
} from "./unhandledRequestGuard";

// An unexpected console.error / console.warn fails the test (see consoleGuard.ts). Installed
// before anything else logs; not a vi.spyOn, so vi.restoreAllMocks() leaves it in place.
installConsoleGuard();

/**
 * Runs every step, even after one throws, then throws what failed: the error itself if one did,
 * otherwise one error carrying all of their messages, so no check hides another.
 */
function runAll(steps: (() => Error | null | void)[]) {
  const errors: unknown[] = [];
  for (const step of steps) {
    try {
      const error = step();
      if (error) errors.push(error);
    } catch (error) {
      errors.push(error);
    }
  }
  if (errors.length === 1) throw errors[0];
  if (errors.length > 1) {
    const message = `Several checks failed:\n\n${errors
      .map((error) => (error instanceof Error ? (error.stack ?? error.message) : String(error)))
      .join("\n\n")}`;
    const combined = new Error(message);
    combined.stack = `Error: ${message}`;
    throw combined;
  }
}

// A request no handler covers is stopped (fetch rejects, nothing reaches the network) and fails
// the test that sent it, or the file if it lands outside a test (see unhandledRequestGuard.ts).
// The listener is the server's for the whole file; server.resetHandlers() doesn't touch it.
beforeAll(() => {
  server.listen({ onUnhandledRequest: blockUnhandledRequest });
  installUnhandledRequestGuard(server);
});

// The app session and the app-load bootstrap are module-level. Every test starts with a fresh
// session ("unknown", no token, no subscribers, no logout channel) and no bootstrap; installed
// before the test too, so the app session's real BroadcastChannel is never opened.
beforeEach(() => {
  beginConsoleGuardTest();
  beginUnhandledRequestGuardTest(expect.getState().currentTestName);
  resetAuthSessionForTests();
  resetSessionBootstrap();
});

afterEach(() => {
  runAll([
    // Testing Library unmounts after each test by itself only when Vitest's globals are on; they
    // aren't here, so do it explicitly.
    () => cleanup(),
    // Close the session's (fake) channel and forget anything a test left in flight.
    () => resetAuthSessionForTests(),
    () => resetSessionBootstrap(),
    // Drop the handlers a test added with server.use(...).
    () => server.resetHandlers(),
    // The checks last, so a request or warning from the cleanup above counts too. The setup
    // file's afterEach runs after the test file's own (Vitest's default sequence.hooks "stack").
    endUnhandledRequestGuardTest,
    endConsoleGuardTest,
  ]);
});

afterAll(() => {
  runAll([() => server.close(), endUnhandledRequestGuardFile, endConsoleGuardFile]);
});
