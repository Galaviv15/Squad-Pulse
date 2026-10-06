import { QueryClient } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { playerBody } from "@/test/msw/squad";
import {
  onPlayerDeleted,
  onPlayerWritten,
  playerQueryKey,
  SQUAD_PLAYERS_QUERY_KEY,
  SQUAD_QUERY_KEY,
  squadPlayersQuery,
} from "./players";

/** A client holding a list, two cards and a summary, none of them invalidated. */
function seeded() {
  const queryClient = new QueryClient();
  queryClient.setQueryData(squadPlayersQuery({ status: "active" }).queryKey, [playerBody()]);
  queryClient.setQueryData(squadPlayersQuery({ status: "all" }).queryKey, [playerBody()]);
  queryClient.setQueryData(playerQueryKey("p1"), playerBody());
  queryClient.setQueryData(playerQueryKey("p2"), playerBody({ id: "p2" }));
  queryClient.setQueryData([...SQUAD_QUERY_KEY, "summary"], { playerCount: 2 });
  queryClient.setQueryData(["auth", "me"], { id: "u1" });
  return queryClient;
}

const invalidated = (queryClient: QueryClient) =>
  queryClient
    .getQueryCache()
    .getAll()
    .filter((query) => query.state.isInvalidated)
    .map((query) => query.queryKey);

describe("squad query keys", () => {
  it("puts every squad query under one prefix", () => {
    expect(SQUAD_PLAYERS_QUERY_KEY.slice(0, 1)).toEqual(SQUAD_QUERY_KEY);
    expect(playerQueryKey("p1")).toEqual(["squad", "player", "p1"]);
  });

  it("never matches a card with the list prefix", async () => {
    const queryClient = seeded();

    await queryClient.invalidateQueries({ queryKey: SQUAD_PLAYERS_QUERY_KEY });

    expect(invalidated(queryClient)).toEqual([
      ["squad", "players", { status: "active" }],
      ["squad", "players", { status: "all" }],
    ]);
  });
});

describe("onPlayerWritten", () => {
  it("puts the player into its card, then invalidates every squad query and nothing else", () => {
    const queryClient = seeded();
    const written = playerBody({ fullName: "Renamed", version: 3 });

    onPlayerWritten(queryClient, written);

    expect(queryClient.getQueryData(playerQueryKey("p1"))).toEqual(written);
    expect(invalidated(queryClient)).toHaveLength(5);
    expect(invalidated(queryClient)).not.toContainEqual(["auth", "me"]);
  });

  it("creates the card's entry for a new player", () => {
    const queryClient = new QueryClient();

    onPlayerWritten(queryClient, playerBody({ id: "new" }));

    expect(queryClient.getQueryData(playerQueryKey("new"))).toMatchObject({ id: "new" });
  });
});

describe("onPlayerDeleted", () => {
  it("drops the deleted player's card and invalidates every other squad query", () => {
    const queryClient = seeded();

    onPlayerDeleted(queryClient, "p1");

    expect(queryClient.getQueryCache().find({ queryKey: playerQueryKey("p1") })).toBeUndefined();
    expect(invalidated(queryClient)).toEqual([
      [...SQUAD_PLAYERS_QUERY_KEY, { status: "active" }],
      [...SQUAD_PLAYERS_QUERY_KEY, { status: "all" }],
      playerQueryKey("p2"),
      [...SQUAD_QUERY_KEY, "summary"],
    ]);
  });

  it("drops only that card: another player's id that starts the same stays", () => {
    const queryClient = seeded();
    queryClient.setQueryData(playerQueryKey("p10"), playerBody({ id: "p10" }));

    onPlayerDeleted(queryClient, "p1");

    expect(queryClient.getQueryData(playerQueryKey("p10"))).toBeDefined();
  });
});
