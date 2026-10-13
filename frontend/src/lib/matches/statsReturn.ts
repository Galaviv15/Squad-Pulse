import { useSearchParams } from "react-router";
import { parseStatsFilter, STATS_PATH, type StatsFilter } from "./statsFilter";

/**
 * The way from a stats row to the create-player form and back (KAN-77). The form's URL carries
 * the stats screen's filter as its own `filter` parameter, and the way back is rebuilt from it
 * with parseStatsFilter: only the one known value survives, so neither a forged parameter nor
 * anything in history state can send the user anywhere but /app/stats (no open redirect: no URL
 * is ever taken from the request). The row to focus travels in history state as a row id only,
 * looked up among the rows shown, never followed.
 */

/** /app/stats, with `?filter=unlinked` for that filter. */
export function statsPath(filter: StatsFilter): string {
  return filter === "unlinked" ? `${STATS_PATH}?filter=unlinked` : STATS_PATH;
}

/** /app/stats/{rowId}/new-player, keeping the stats screen's filter. */
export function newPlayerFromStatsPath(rowId: string, filter: StatsFilter): string {
  const path = `${STATS_PATH}/${encodeURIComponent(rowId)}/new-player`;
  return filter === "unlinked" ? `${path}?filter=unlinked` : path;
}

/** The create-player path for a row, from the current URL's filter (the stats screen's or the form's). */
export function useNewPlayerFromStatsPath(): (rowId: string) => string {
  const [searchParams] = useSearchParams();
  const filter = parseStatsFilter(searchParams);
  return (rowId) => newPlayerFromStatsPath(rowId, filter);
}

/** History state the form hands the stats screen: the row to focus, and the created player's name. */
export interface StatsReturnState {
  statsRowId: string;
  /** Set after a save: "<name> נוסף לסגל וקושר." is announced. Absent on "ביטול". */
  createdPlayerName?: string;
}

export function statsReturnState(rowId: string, createdPlayerName?: string): StatsReturnState {
  return createdPlayerName === undefined
    ? { statsRowId: rowId }
    : { statsRowId: rowId, createdPlayerName };
}

/** The return state from history, or null. State is whatever history holds: check it. */
export function statsReturnFromState(
  state: unknown,
): { rowId: string; createdPlayerName: string | null } | null {
  if (typeof state !== "object" || state === null) {
    return null;
  }
  const { statsRowId, createdPlayerName } = state as Partial<StatsReturnState>;
  if (typeof statsRowId !== "string") {
    return null;
  }
  return {
    rowId: statsRowId,
    createdPlayerName: typeof createdPlayerName === "string" ? createdPlayerName : null,
  };
}
