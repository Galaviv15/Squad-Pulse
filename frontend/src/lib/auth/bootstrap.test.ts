import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { authSession } from "@/lib/api/session";
import { accessToken } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { resetSessionBootstrap, startSessionBootstrap } from "./bootstrap";

/** POST /auth/refresh answering 502 first, then a token. Counts the calls. */
function refreshFailingOnce() {
  const calls = { count: 0 };
  server.use(
    http.post("/auth/refresh", () => {
      calls.count++;
      return calls.count === 1 ? new HttpResponse(null, { status: 502 }) : accessToken("t1");
    }),
  );
  return calls;
}

describe("startSessionBootstrap", () => {
  it("refreshes once, and keeps answering with that refresh once it has settled", async () => {
    const refresh = refreshFailingOnce();

    await expect(startSessionBootstrap()).rejects.toHaveProperty("status", 502);
    await expect(startSessionBootstrap()).rejects.toHaveProperty("status", 502);

    expect(refresh.count).toBe(1);
    expect(authSession.getStatus()).toBe("unknown");
  });

  it("refreshes again after a reset (the retry button)", async () => {
    const refresh = refreshFailingOnce();
    await startSessionBootstrap().catch(() => undefined);

    resetSessionBootstrap();
    await startSessionBootstrap();

    expect(refresh.count).toBe(2);
    expect(authSession.getStatus()).toBe("authenticated");
  });
});
