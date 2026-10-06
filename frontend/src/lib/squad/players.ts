import {
  keepPreviousData,
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import { apiJson } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import { PLAYER_ERROR_CODES } from "./errorCodes";
import { squadFiltersToSearchParams, type SquadFilters } from "./filters";
import type { CreatePlayerBody, UpdatePlayerBody } from "./form";
import type { Player } from "./types";

/**
 * The prefix of every squad query's key: the lists (SQUAD_PLAYERS_QUERY_KEY), each player's card
 * (playerQueryKey) and KAN-51's summary (["squad", "summary"]). Invalidating it after a player
 * write refetches all of them.
 */
export const SQUAD_QUERY_KEY = ["squad"] as const;

/** The prefix of every player-list query's key, one per set of filters. */
export const SQUAD_PLAYERS_QUERY_KEY = [...SQUAD_QUERY_KEY, "players"] as const;

/**
 * A player's card query key. "player", not "players": the list prefix never matches a card (keys
 * match element by element).
 */
export function playerQueryKey(playerId: string) {
  return [...SQUAD_QUERY_KEY, "player", playerId] as const;
}

/** The list's URL: GET /squad/players, with no query string for the default (active) list. */
export function squadPlayersPath(filters: SquadFilters): string {
  const query = squadFiltersToSearchParams(filters).toString();
  return query === "" ? "/squad/players" : `/squad/players?${query}`;
}

/** One player's API URL: GET / PUT /squad/players/{id}. */
export function playerApiPath(playerId: string): string {
  return `/squad/players/${encodeURIComponent(playerId)}`;
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

/** One player, released ones included (active: false); another club's or a missing id is a 404. */
export function playerQuery(playerId: string) {
  return queryOptions({
    queryKey: playerQueryKey(playerId),
    queryFn: ({ signal }) => apiJson<Player>(playerApiPath(playerId), { signal }),
  });
}

export function usePlayer(
  playerId: string,
  { refetchOnMount }: { refetchOnMount?: "always" } = {},
) {
  return useQuery({ ...playerQuery(playerId), refetchOnMount });
}

/**
 * After any write that returns the player (create, edit, and KAN-59's release / re-activate):
 * puts the returned player into its card's cache, so the card shows it at once, then invalidates
 * every squad query (lists, cards, the summary). Not awaited: the write is done; the refetches
 * run in the background.
 */
export function onPlayerWritten(queryClient: QueryClient, player: Player) {
  queryClient.setQueryData(playerQueryKey(player.id), player);
  void queryClient.invalidateQueries({ queryKey: SQUAD_QUERY_KEY });
}

/** POST /squad/players → the new player (201). */
export function useCreatePlayer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreatePlayerBody) =>
      apiJson<Player>("/squad/players", { method: "POST", json: body }),
    onSuccess: (player) => onPlayerWritten(queryClient, player),
  });
}

/**
 * PUT /squad/players/{id} → the updated player. A 409 PLAYER_RELEASED means the cached copies are
 * out of date (the player was released meanwhile), so the squad queries are invalidated then too.
 */
export function useUpdatePlayer(playerId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdatePlayerBody) =>
      apiJson<Player>(playerApiPath(playerId), { method: "PUT", json: body }),
    onSuccess: (player) => onPlayerWritten(queryClient, player),
    onError: (error) => {
      if (
        error instanceof ApiError &&
        error.status === 409 &&
        error.code === PLAYER_ERROR_CODES.PLAYER_RELEASED
      ) {
        void queryClient.invalidateQueries({ queryKey: SQUAD_QUERY_KEY });
      }
    },
  });
}
