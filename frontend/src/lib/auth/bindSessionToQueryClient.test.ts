import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { createBrowserSession } from "@/lib/api/session";
import { loginReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { bindSessionToQueryClient } from "./bindSessionToQueryClient";

describe("bindSessionToQueryClient", () => {
  it("empties the cache when the session ends, and only then", async () => {
    server.use(loginReturns("t1"));
    const session = createBrowserSession();
    const queryClient = new QueryClient();
    bindSessionToQueryClient(session, queryClient);
    queryClient.setQueryData(["squad", "players"], ["p1"]);

    await session.login("coach@example.com", "pw");
    expect(queryClient.getQueryData(["squad", "players"])).toEqual(["p1"]);

    session.clear();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it("stops once unbound", () => {
    const session = createBrowserSession();
    const queryClient = new QueryClient();
    const unbind = bindSessionToQueryClient(session, queryClient);
    queryClient.setQueryData(["squad", "players"], ["p1"]);

    unbind();
    session.clear();

    expect(queryClient.getQueryData(["squad", "players"])).toEqual(["p1"]);
  });
});
