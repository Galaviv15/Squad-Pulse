import { http, HttpResponse } from "msw";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiUrl } from "@/lib/config";
import { server } from "./msw/server";
import {
  beginUnhandledRequestGuardTest,
  endUnhandledRequestGuardFile,
  endUnhandledRequestGuardTest,
  expectUnhandledRequest,
} from "./unhandledRequestGuard";

// The guard is installed by src/test/setup.ts. These tests call its check themselves, so a
// request they send on purpose is consumed here and doesn't fail them; each starts a "test" again
// afterwards, as setup.ts's beforeEach would.

const SUMMARY = "http://localhost:3000/squad/summary";

/** Runs the guard's end-of-test check now and starts a fresh test. */
function checkNow() {
  const error = endUnhandledRequestGuardTest();
  beginUnhandledRequestGuardTest("unhandledRequestGuard.test.ts");
  return error;
}

/** Sends a request and waits until MSW has answered it (rejected, when no handler covers it). */
async function send(path: string, init?: RequestInit) {
  await fetch(apiUrl(path), init).catch(() => {});
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("unhandled request guard", () => {
  it("reports an unmocked request with its method and URL", async () => {
    await send("/squad/summary");
    await send("/squad/players", { method: "POST" });

    const message = checkNow()?.message;

    expect(message).toContain("Unmocked request(s) in this test:");
    expect(message).toContain(`GET ${SUMMARY}`);
    expect(message).toContain("POST http://localhost:3000/squad/players");
    expect(message).toContain("server.use(...)");
  });

  it("stops the request: fetch rejects with MSW's network error, nothing goes out", async () => {
    const failure = await fetch(apiUrl("/squad/summary")).catch((error: unknown) => error);

    expect(failure).toBeInstanceOf(TypeError);
    expect((failure as TypeError).message).toBe("Failed to fetch");
    // The network-error Response blockUnhandledRequest threw: MSW answered the request itself. A
    // request let through would fail differently ("fetch failed", a connection error).
    const { cause } = failure as { cause?: unknown };
    expect(cause).toBeInstanceOf(Response);
    expect((cause as Response).type).toBe("error");
    expect(checkNow()).not.toBeNull();
  });

  it("reports nothing for a handled request", async () => {
    server.use(http.get("/squad/summary", () => HttpResponse.json({})));

    const response = await fetch(apiUrl("/squad/summary"));

    expect(response.status).toBe(200);
    expect(checkNow()).toBeNull();
  });

  it("reports a request while the test mocks console.error, and MSW logs nothing", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    await send("/squad/summary");

    expect(checkNow()?.message).toContain(`GET ${SUMMARY}`);
    expect(consoleError).not.toHaveBeenCalled();
  });

  it("still reports after server.resetHandlers()", async () => {
    server.resetHandlers();

    await send("/squad/summary");

    expect(checkNow()?.message).toContain(`GET ${SUMMARY}`);
  });

  // Runs after the previous tests, whose afterEach called vi.restoreAllMocks().
  it("is still in place after a test that restored all mocks", async () => {
    await send("/squad/summary");

    expect(checkNow()?.message).toContain(`GET ${SUMMARY}`);
  });

  it("keeps a request sent outside a test for the end of the file", async () => {
    endUnhandledRequestGuardTest();
    await send("/squad/summary");
    beginUnhandledRequestGuardTest("unhandledRequestGuard.test.ts");

    expect(checkNow()).toBeNull();
    const message = endUnhandledRequestGuardFile()?.message;
    expect(message).toContain("Unmocked request(s) outside a test");
    expect(message).toContain(`GET ${SUMMARY}`);
    // The last test that ran, as a pointer.
    expect(message).toContain('"unhandledRequestGuard.test.ts"');
    expect(endUnhandledRequestGuardFile()).toBeNull();
  });

  describe("expectUnhandledRequest (the opt-out)", () => {
    it("accepts the expected request", async () => {
      expectUnhandledRequest("GET", "/squad/summary");

      await send("/squad/summary");

      expect(checkNow()).toBeNull();
    });

    it("fails when the expected request never happens", () => {
      expectUnhandledRequest("GET", "/squad/summary");

      expect(checkNow()?.message).toBe(
        `Expected unmocked request(s) that never happened:\n  GET ${SUMMARY}`,
      );
    });

    it("still reports any other unmocked request, and a second expected one", async () => {
      expectUnhandledRequest("GET", "/squad/summary");

      await send("/squad/summary");
      await send("/squad/summary");
      await send("/squad/summary", { method: "POST" });
      await send("/squad/summary?x=1");

      const message = checkNow()?.message;
      expect(message).toBe(
        "Unmocked request(s) in this test:\n" +
          `  GET ${SUMMARY}\n  POST ${SUMMARY}\n  GET ${SUMMARY}?x=1\n` +
          "Add a handler with server.use(...), or fix the code if the request shouldn't happen.",
      );
    });

    it("applies to its own test only", async () => {
      expectUnhandledRequest("GET", "/squad/summary");
      checkNow();

      await send("/squad/summary");

      expect(checkNow()?.message).toContain(`GET ${SUMMARY}`);
    });
  });

  // End to end: the check in setup.ts's afterEach fails the test.
  it.fails("fails a test whose unmocked request lands after its last assertion", () => {
    expect(true).toBe(true);
    void fetch(apiUrl("/squad/summary")).catch(() => {});
  });

  it.fails("fails a test that mocks console.error and sends an unmocked request", async () => {
    vi.spyOn(console, "error").mockImplementation(() => {});

    await send("/squad/summary");
  });

  it.fails("fails a test whose expected unmocked request never happens", () => {
    expectUnhandledRequest("GET", "/squad/summary");
  });
});
