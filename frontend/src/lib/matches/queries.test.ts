import { QueryObserver } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { createQueryClient } from "@/lib/queryClient";
import { failure500, gameDetailsBody, gameDetailsReturns } from "@/test/msw/matches";
import { server } from "@/test/msw/server";
import { MATCHES_DATA_QUERY_KEY, gameDetailsQuery } from "./queries";

/** Observes `id`'s game query on `client` until it settles; its final state. */
function settle(client: ReturnType<typeof createQueryClient>, id: string) {
  const observer = new QueryObserver(client, gameDetailsQuery(id));
  return new Promise<ReturnType<typeof observer.getCurrentResult>>((resolve) => {
    const unsubscribe = observer.subscribe((result) => {
      if (result.status !== "pending" && !result.isFetching) {
        unsubscribe();
        resolve(result);
      }
    });
  });
}

describe("gameDetailsQuery (the app's QueryClient: createQueryClient)", () => {
  it("is under MATCHES_DATA_QUERY_KEY", () => {
    expect(gameDetailsQuery("g1").queryKey.slice(0, MATCHES_DATA_QUERY_KEY.length)).toEqual([
      ...MATCHES_DATA_QUERY_KEY,
    ]);
  });

  it("never retries a 404: one request, then the error", async () => {
    const details = gameDetailsReturns();
    server.use(details.handler);
    const client = createQueryClient();

    const result = await settle(client, "no-such-game");

    expect(result.status).toBe("error");
    expect(result.error).toMatchObject({ status: 404 });
    expect(result.failureCount).toBe(1);
    expect(details.requests.count).toBe(1);
  });

  it("does retry a 500 (for contrast: the same client, no retry delay)", async () => {
    const details = gameDetailsReturns(failure500);
    server.use(details.handler);
    const client = createQueryClient();
    client.setQueryDefaults(MATCHES_DATA_QUERY_KEY, { retryDelay: 0 });

    const result = await settle(client, "g1");

    expect(result.status).toBe("error");
    expect(details.requests.count).toBe(3);
  });

  it("is refetched while observed when MATCHES_DATA_QUERY_KEY is invalidated", async () => {
    const details = gameDetailsReturns(gameDetailsBody());
    server.use(details.handler);
    const client = createQueryClient();
    const observer = new QueryObserver(client, gameDetailsQuery("details-2026-09-05"));
    const unsubscribe = observer.subscribe(() => {});
    try {
      await client.fetchQuery(gameDetailsQuery("details-2026-09-05"));
      const before = details.requests.count;

      await client.invalidateQueries({ queryKey: MATCHES_DATA_QUERY_KEY });

      expect(details.requests.count).toBe(before + 1);
    } finally {
      unsubscribe();
    }
  });
});
