import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { apiUrl } from "@/lib/config";
import { server } from "./server";

// What fetch rejects with under onUnhandledRequest: "error" (src/test/setup.ts).
const UNHANDLED = /"error" strategy for the "onUnhandledRequest" option/;

describe("mock API", () => {
  it("answers a same-origin relative URL, as the app calls the API", async () => {
    server.use(http.get("/clubs/me", () => HttpResponse.json({ id: "c1", name: "Test FC" })));

    const response = await fetch(apiUrl("/clubs/me"));

    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({ id: "c1", name: "Test FC" });
  });

  it("fails a request that no handler covers", async () => {
    await expect(fetch(apiUrl("/squad/players"))).rejects.toThrow(UNHANDLED);
  });

  it("forgets a test's handlers after it", async () => {
    // The /clubs/me handler from the first test is gone again.
    await expect(fetch(apiUrl("/clubs/me"))).rejects.toThrow(UNHANDLED);
  });
});
