import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiUrl } from "@/lib/config";
import { server } from "./server";

// What fetch rejects with under onUnhandledRequest: "error" (src/test/setup.ts).
const UNHANDLED = /"error" strategy for the "onUnhandledRequest" option/;

/**
 * MSW also logs an unhandled request as a console.error, which the console guard would fail the
 * test on: mock it, so the test can expect it (and assert it was logged).
 */
function expectUnhandledLogged(path: string) {
  const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
  return () =>
    expect(consoleError).toHaveBeenCalledExactlyOnceWith(
      expect.stringMatching(
        new RegExp(
          `^\\[MSW\\] Error: intercepted a request without a matching request handler:\\s+• GET ${path}\\s`,
        ),
      ),
    );
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("mock API", () => {
  it("answers a same-origin relative URL, as the app calls the API", async () => {
    server.use(http.get("/clubs/me", () => HttpResponse.json({ id: "c1", name: "Test FC" })));

    const response = await fetch(apiUrl("/clubs/me"));

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ id: "c1", name: "Test FC" });
  });

  it("fails a request that no handler covers", async () => {
    const assertLogged = expectUnhandledLogged("/squad/players");

    await expect(fetch(apiUrl("/squad/players"))).rejects.toThrow(UNHANDLED);
    assertLogged();
  });

  it("forgets a test's handlers after it", async () => {
    // The /clubs/me handler from the first test is gone again.
    const assertLogged = expectUnhandledLogged("/clubs/me");

    await expect(fetch(apiUrl("/clubs/me"))).rejects.toThrow(UNHANDLED);
    assertLogged();
  });
});
