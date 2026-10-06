import {
  keepPreviousData,
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import { apiFetch, apiJson } from "@/lib/api/client";
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
 * After any write that returns the player (create, edit, release, re-activate):
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

/**
 * After a permanent delete (or a delete answered 404: the player is gone either way): stops and
 * drops the player's card query, then invalidates every squad query (lists, the summary).
 *
 * Never onPlayerWritten for a delete: invalidating the card would refetch the deleted player and
 * show "not found". And while the deleted player's card is still mounted, not even this: a mounted
 * card whose query was removed builds a new one on its next render and fetches it (a GET → 404).
 * So the card calls it only once it has navigated away; the squad table at once.
 */
export function onPlayerDeleted(queryClient: QueryClient, playerId: string) {
  const queryKey = playerQueryKey(playerId);
  void queryClient.cancelQueries({ queryKey, exact: true });
  queryClient.removeQueries({ queryKey, exact: true });
  void queryClient.invalidateQueries({ queryKey: SQUAD_QUERY_KEY });
}

/** The 409 codes saying the cached player is out of date: its state or version has moved on. */
const OUT_OF_DATE_CODES: ReadonlySet<string | null> = new Set([
  PLAYER_ERROR_CODES.STALE_VERSION,
  PLAYER_ERROR_CODES.PLAYER_ALREADY_RELEASED,
  PLAYER_ERROR_CODES.PLAYER_ALREADY_ACTIVE,
  PLAYER_ERROR_CODES.PLAYER_RELEASED,
]);

/**
 * After a failed player write: a 409 saying the cache is out of date, or a 404 (the player is
 * gone), invalidates every squad query, so the card and the lists show the server's state.
 */
function invalidateIfOutOfDate(queryClient: QueryClient, error: unknown) {
  if (
    error instanceof ApiError &&
    (error.status === 404 || (error.status === 409 && OUT_OF_DATE_CODES.has(error.code)))
  ) {
    void queryClient.invalidateQueries({ queryKey: SQUAD_QUERY_KEY });
  }
}

/*
 * The lifecycle and photo writes take the player's id as a mutation variable, not a hook argument:
 * the squad table acts on any of its rows with one hook instance, and a mutation's variables are
 * the request it sent, so a late answer can't be mistaken for another player's.
 */

export interface ReleasePlayerVariables {
  playerId: string;
  /** The version of the data the user confirmed from (card or row). */
  version: number;
}

/** POST /squad/players/{id}/release {version} → the released player. */
export function useReleasePlayer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ playerId, version }: ReleasePlayerVariables) =>
      apiJson<Player>(`${playerApiPath(playerId)}/release`, {
        method: "POST",
        json: { version },
      }),
    onSuccess: (player) => onPlayerWritten(queryClient, player),
    onError: (error) => invalidateIfOutOfDate(queryClient, error),
  });
}

export interface ReactivatePlayerVariables {
  playerId: string;
  version: number;
  /** A full replacement: null comes back without a number, not with the old one. */
  jerseyNumber: number | null;
}

/** POST /squad/players/{id}/reactivate {version, jerseyNumber} → the active player. */
export function useReactivatePlayer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ playerId, version, jerseyNumber }: ReactivatePlayerVariables) =>
      apiJson<Player>(`${playerApiPath(playerId)}/reactivate`, {
        method: "POST",
        json: { version, jerseyNumber },
      }),
    onSuccess: (player) => onPlayerWritten(queryClient, player),
    onError: (error) => invalidateIfOutOfDate(queryClient, error),
  });
}

/**
 * DELETE /squad/players/{id} (ADMIN) → 204. A 404 resolves too: the server answers it when the
 * player is already gone (deleted from another tab), the outcome the user asked for. No cache
 * work here: the caller runs onPlayerDeleted when it's safe (see there).
 */
export function useDeletePlayer() {
  return useMutation({
    mutationFn: async ({ playerId }: { playerId: string }) => {
      try {
        await apiFetch(playerApiPath(playerId), { method: "DELETE" });
      } catch (error) {
        if (!(error instanceof ApiError && error.status === 404)) {
          throw error;
        }
      }
    },
  });
}

/**
 * After a photo upload or removal, which return no player (and leave its version as it was): sets
 * the card's cached hasPhoto at once, so the card shows the photo or the initials without waiting,
 * then invalidates every squad query (the lists' hasPhoto, the card from the server).
 */
function onPhotoWritten(queryClient: QueryClient, playerId: string, hasPhoto: boolean) {
  queryClient.setQueryData<Player>(
    playerQueryKey(playerId),
    (player) => player && { ...player, hasPhoto },
  );
  void queryClient.invalidateQueries({ queryKey: SQUAD_QUERY_KEY });
}

/**
 * PUT /squad/players/{id}/photo, the image in the multipart part `file` → 204. A replaced photo
 * keeps its path, so the caller refreshes the shown image itself (useAuthorizedImage's refresh key).
 */
export function useUploadPlayerPhoto() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ playerId, file }: { playerId: string; file: File }) => {
      const formData = new FormData();
      formData.append("file", file);
      return apiFetch(`${playerApiPath(playerId)}/photo`, { method: "PUT", formData });
    },
    onSuccess: (_, { playerId }) => onPhotoWritten(queryClient, playerId, true),
    onError: (error) => invalidateIfOutOfDate(queryClient, error),
  });
}

/** DELETE /squad/players/{id}/photo → 204, also when there was none. */
export function useRemovePlayerPhoto() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ playerId }: { playerId: string }) =>
      apiFetch(`${playerApiPath(playerId)}/photo`, { method: "DELETE" }),
    onSuccess: (_, { playerId }) => onPhotoWritten(queryClient, playerId, false),
    onError: (error) => invalidateIfOutOfDate(queryClient, error),
  });
}
