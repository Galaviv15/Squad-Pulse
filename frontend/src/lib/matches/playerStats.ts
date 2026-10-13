import { queryOptions, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch, apiJson } from "@/lib/api/client";
import { ApiError } from "@/lib/api/errors";
import type { CreatePlayerBody } from "@/lib/squad/form";
import { onPlayerWritten, playerApiPath } from "@/lib/squad/players";
import type { Player } from "@/lib/squad/types";
import { PLAYER_STATS_ERROR_CODES } from "./playerStatsErrorCodes";
import { MATCHES_DATA_QUERY_KEY } from "./queries";
import type { MatchDataState } from "./types";

/**
 * The player statistics API's types (KAN-72), mirroring match.PlayerStatsResponse exactly. Every
 * field is always present: `linkedPlayer`, `suggestedPlayer` and `lastUpdatedAt` are null when
 * empty, never absent. `id` is our own opaque id, never a football.org.il one, and is never shown.
 */

/** match.PlayerStatsResponse.PlayerRef: one of the club's own players. */
export interface StatsPlayerRef {
  id: string;
  fullName: string;
  active: boolean;
}

/** match.PlayerStatsResponse.Row: one scraped player's season totals, all competitions together. */
export interface PlayerStatsRow {
  id: string;
  /** As the site shows it: surname first. */
  name: string;
  games: number;
  goals: number;
  /** League and State Cup. */
  yellowCardsLeagueAndCup: number;
  /** Toto Cup. */
  yellowCardsToto: number;
  redCards: number;
  starts: number;
  cameOnAsSub: number;
  subbedOff: number;
  /** Can exceed 90 × games (added time): never flagged. */
  minutes: number;
  linkedPlayer: StatsPlayerRef | null;
  /** A likely match by name, only while not linked: applied only by a user's click. */
  suggestedPlayer: StatsPlayerRef | null;
}

/** match.PlayerStatsResponse: GET /matches/player-stats. */
export interface PlayerStats {
  state: MatchDataState;
  /** When the shown snapshot was scraped; null without one. */
  lastUpdatedAt: string | null;
  /** In the site's order: shown in this order, never re-sorted. */
  players: PlayerStatsRow[];
}

/**
 * Under the scraped data's prefix (MATCHES_DATA_QUERY_KEY), not beside it: a refresh run's end
 * invalidates that prefix (useRefreshStatus), and every refresh scrapes the stats too (KAN-73), so
 * the stats screen refetches with the fixtures and the table.
 */
export const PLAYER_STATS_QUERY_KEY = [...MATCHES_DATA_QUERY_KEY, "player-stats"] as const;

/** GET /matches/player-stats. TanStack's defaults, like the fixtures. */
export const playerStatsQuery = queryOptions({
  queryKey: PLAYER_STATS_QUERY_KEY,
  queryFn: ({ signal }) => apiJson<PlayerStats>("/matches/player-stats", { signal }),
});

export function usePlayerStats() {
  return useQuery(playerStatsQuery);
}

function linkApiPath(rowId: string) {
  return `/matches/player-stats/${encodeURIComponent(rowId)}/link`;
}

/**
 * After a link write: refetch the whole list, never patch the written row. A link or unlink
 * changes other rows too (a linked player is never suggested, so another row's suggestion can
 * appear or go), and only the server knows which. A 404 (the row or the player is gone: a refresh
 * replaced the snapshot) and a 409 (the player is linked elsewhere) mean the list shown is out of
 * date, so they refetch too; any other failure changed nothing.
 */
function useStatsWrite<V, R>(mutationFn: (variables: V) => Promise<R>) {
  const queryClient = useQueryClient();
  const refetch = () => void queryClient.invalidateQueries({ queryKey: PLAYER_STATS_QUERY_KEY });
  return useMutation({
    mutationFn,
    onSuccess: refetch,
    onError: (error) => {
      if (error instanceof ApiError && (error.status === 404 || error.status === 409)) {
        refetch();
      }
    },
  });
}

export interface LinkVariables {
  rowId: string;
  playerId: string;
}

/**
 * PUT /matches/player-stats/{id}/link {playerId} → the row. Sets or replaces the row's link. 409
 * PLAYER_ALREADY_LINKED if that player is linked to another scraped player.
 */
export function useLinkStatsPlayer() {
  return useStatsWrite(({ rowId, playerId }: LinkVariables) =>
    apiJson<PlayerStatsRow>(linkApiPath(rowId), { method: "PUT", json: { playerId } }),
  );
}

/** DELETE /matches/player-stats/{id}/link → 204, also when there was no link. */
export function useUnlinkStatsRow() {
  return useStatsWrite(({ rowId }: { rowId: string }) =>
    apiFetch(linkApiPath(rowId), { method: "DELETE" }),
  );
}

/**
 * DELETE /squad/players/{id}/stats-link → 204, linked or not: the way to unlink a squad player
 * whose scraped row is gone, and the delete dialog's unlink step. A 404 means the player is gone;
 * the delete dialog treats that as deleted and runs its own onDeleted, which drops the player's
 * card query before invalidating the squad queries, so this never touches them.
 */
export function useRemovePlayerStatsLink() {
  return useStatsWrite(({ playerId }: { playerId: string }) =>
    apiFetch(`${playerApiPath(playerId)}/stats-link`, { method: "DELETE" }),
  );
}

export interface CreatePlayerFromStatsVariables {
  rowId: string;
  /** Exactly what POST /squad/players takes (formValuesToCreateBody). */
  body: CreatePlayerBody;
}

/**
 * POST /matches/player-stats/{id}/player (KAN-77) → the new player (201): creates a squad player
 * and links the row to it, in one backend transaction (both or neither). On success the squad
 * caches are updated as for any create (onPlayerWritten) and the stats are refetched (the row is
 * linked now). A 404 (the row is gone: a refresh replaced the snapshot) and the 409s that mean the
 * row's link changed meanwhile (STATS_ROW_ALREADY_LINKED, CONCURRENT_MODIFICATION) refetch the
 * stats too; nothing was created, so the squad is untouched. Never retried (mutations never are).
 */
export function useCreatePlayerFromStats() {
  const queryClient = useQueryClient();
  const refetchStats = () =>
    void queryClient.invalidateQueries({ queryKey: PLAYER_STATS_QUERY_KEY });
  return useMutation({
    mutationFn: ({ rowId, body }: CreatePlayerFromStatsVariables) =>
      apiJson<Player>(`/matches/player-stats/${encodeURIComponent(rowId)}/player`, {
        method: "POST",
        json: body,
      }),
    onSuccess: (player) => {
      onPlayerWritten(queryClient, player);
      refetchStats();
    },
    onError: (error) => {
      if (isStatsOutOfDate(error)) {
        refetchStats();
      }
    },
  });
}

/** A create-and-link failure that means the stats shown are out of date. */
function isStatsOutOfDate(error: Error): boolean {
  return (
    error instanceof ApiError &&
    (error.status === 404 ||
      (error.status === 409 &&
        (error.code === PLAYER_STATS_ERROR_CODES.STATS_ROW_ALREADY_LINKED ||
          error.code === PLAYER_STATS_ERROR_CODES.CONCURRENT_MODIFICATION)))
  );
}

/**
 * What a failed link write (link, unlink) says, an i18n key. Compared by status and `code`, never
 * the English message.
 */
export function linkErrorKey(error: Error): string {
  if (error instanceof ApiError) {
    if (error.status === 409 && error.code === PLAYER_STATS_ERROR_CODES.PLAYER_ALREADY_LINKED) {
      return "stats.errors.alreadyLinked";
    }
    if (error.status === 404) {
      return "stats.errors.notFound";
    }
    if (error.status === 403) {
      return "squad.noPermission";
    }
  }
  return "squad.dialog.failed";
}
