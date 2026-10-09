import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { apiUrl } from "@/lib/config";
import { expectUnhandledRequest } from "../unhandledRequestGuard";
import { server } from "./server";

// What fetch rejects with when no handler covers a request (blockUnhandledRequest in
// src/test/unhandledRequestGuard.ts): a network error, so the request never goes out.
async function expectBlocked(path: string) {
  const failure = await fetch(apiUrl(path)).catch((error: unknown) => error);
  expect(failure).toBeInstanceOf(TypeError);
  expect(((failure as { cause?: unknown }).cause as Response).type).toBe("error");
}

describe("mock API", () => {
  it("answers a same-origin relative URL, as the app calls the API", async () => {
    server.use(http.get("/clubs/me", () => HttpResponse.json({ id: "c1", name: "Test FC" })));

    const response = await fetch(apiUrl("/clubs/me"));

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ id: "c1", name: "Test FC" });
  });

  // The guard reports the request (and fails the test if it doesn't happen): expected here.
  it("fails a request that no handler covers", async () => {
    expectUnhandledRequest("GET", "/squad/players");

    await expectBlocked("/squad/players");
  });

  it("forgets a test's handlers after it", async () => {
    // The /clubs/me handler from the first test is gone again.
    expectUnhandledRequest("GET", "/clubs/me");

    await expectBlocked("/clubs/me");
  });
});
