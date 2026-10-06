import { keepPreviousData, queryOptions, useQuery } from "@tanstack/react-query";
import { apiJson } from "@/lib/api/client";
import { squadFiltersToSearchParams, type SquadFilters } from "./filters";
import type { Player } from "./types";

/**
 * The prefix of every player-list query's key, one per set of filters: invalidate it after a
 * player write (create, edit, release, ...) to refetch every list.
 */
export const SQUAD_PLAYERS_QUERY_KEY = ["squad", "players"] as const;

/** The list's URL: GET /squad/players, with no query string for the default (active) list. */
export function squadPlayersPath(filters: SquadFilters): string {
  const query = squadFiltersToSearchParams(filters).toString();
  return query === "" ? "/squad/players" : `/squad/players?${query}`;
}

/**
 * The player list for `filters` (as parseSquadFilters returns them: only valid values, so the
 * server never answers 400), in the server's squad order, which callers keep as is: never
 * re-sort it. On a filter change the previous list stays as placeholder data until the new one
 * arrives (isPlaceholderData), so the table doesn't blank.
 */
export function squadPlayersQuery(filters: SquadFilters) {
  return queryOptions({
    queryKey: [...SQUAD_PLAYERS_QUERY_KEY, filters],
    queryFn: ({ signal }) => apiJson<Player[]>(squadPlayersPath(filters), { signal }),
    placeholderData: keepPreviousData,
  });
}

export function useSquadPlayers(filters: SquadFilters) {
  return useQuery(squadPlayersQuery(filters));
}
