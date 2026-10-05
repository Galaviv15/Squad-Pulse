import { cleanup } from "@testing-library/react";
import { afterAll, afterEach, beforeAll } from "vitest";
// Adds jest-dom matchers (toBeInTheDocument, ...) to Vitest's expect.
import "@testing-library/jest-dom/vitest";
import { server } from "./msw/server";

// A request no handler covers fails the test instead of reaching the network.
beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  // Testing Library unmounts after each test by itself only when Vitest's globals are on; they
  // aren't here, so do it explicitly.
  cleanup();
  // Drop the handlers a test added with server.use(...).
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});
