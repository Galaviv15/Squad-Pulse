import { queryOptions, useQuery } from "@tanstack/react-query";
import { apiJson } from "@/lib/api/client";
import type { Fixtures, LeagueTable, PlayedGameDetails } from "./types";

/** Every matches query's key starts with this. */
export const MATCHES_QUERY_KEY = ["matches"] as const;

/**
 * The scraped data's queries (fixtures, league table, player stats, a game's details), under one
 * prefix: invalidating it (a refresh run's end) refetches them all, and never the refresh status
 * (REFRESH_STATUS_QUERY_KEY, a sibling).
 */
export const MATCHES_DATA_QUERY_KEY = [...MATCHES_QUERY_KEY, "data"] as const;
export const FIXTURES_QUERY_KEY = [...MATCHES_DATA_QUERY_KEY, "fixtures"] as const;
export const LEAGUE_TABLE_QUERY_KEY = [...MATCHES_DATA_QUERY_KEY, "league-table"] as const;

/** GET /matches/fixtures. TanStack's defaults, like the squad summary: every visit refetches. */
export const fixturesQuery = queryOptions({
  queryKey: FIXTURES_QUERY_KEY,
  queryFn: ({ signal }) => apiJson<Fixtures>("/matches/fixtures", { signal }),
});

/** GET /matches/league-table. */
export const leagueTableQuery = queryOptions({
  queryKey: LEAGUE_TABLE_QUERY_KEY,
  queryFn: ({ signal }) => apiJson<LeagueTable>("/matches/league-table", { signal }),
});

export function useFixtures() {
  return useQuery(fixturesQuery);
}

export function useLeagueTable() {
  return useQuery(leagueTableQuery);
}

/**
 * GET /matches/games/{id} (KAN-81): one played game's details. Under MATCHES_DATA_QUERY_KEY, so a
 * refresh run's end refetches an open game page too. A 404 (unknown id, another club's, or one of
 * an earlier mapping) is never retried: shouldRetryQuery never retries a 4xx.
 */
export function gameDetailsQuery(detailsId: string) {
  return queryOptions({
    queryKey: [...MATCHES_DATA_QUERY_KEY, "game", detailsId] as const,
    queryFn: ({ signal }) =>
      apiJson<PlayedGameDetails>(`/matches/games/${encodeURIComponent(detailsId)}`, { signal }),
  });
}

export function useGameDetails(detailsId: string) {
  return useQuery(gameDetailsQuery(detailsId));
}
