import { useSearchParams } from "react-router";
import type { PlayerStatsRow } from "./playerStats";

/** The player statistics screen. */
export const STATS_PATH = "/app/stats";

/** The stats screen's filter: every row (default), or only the rows without a link. */
export const STATS_FILTERS = ["all", "unlinked"] as const;
export type StatsFilter = (typeof STATS_FILTERS)[number];

/** The filter without a `filter` parameter; never written to a URL. */
export const DEFAULT_STATS_FILTER: StatsFilter = "all";

/**
 * The filter in a stats screen URL's `params`, like the matches tab: only a single
 * `filter=unlinked` is the unlinked rows; anything else (none, another value or case, repeated)
 * is all.
 */
export function parseStatsFilter(params: URLSearchParams): StatsFilter {
  const values = params.getAll("filter");
  return values.length === 1 && values[0] === "unlinked" ? "unlinked" : DEFAULT_STATS_FILTER;
}

/**
 * The stats screen's filter, kept in the URL (`?filter=unlinked`, nothing for all). A switch
 * replaces the entry, as the squad's view toggle does.
 */
export function useStatsFilter() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filter = parseStatsFilter(searchParams);
  const setFilter = (next: StatsFilter) => {
    setSearchParams(next === DEFAULT_STATS_FILTER ? {} : { filter: next }, { replace: true });
  };
  return { filter, setFilter };
}

/** The rows `filter` shows, in the server's order. "unlinked" keeps suggested rows too. */
export function filterStatsRows(rows: PlayerStatsRow[], filter: StatsFilter): PlayerStatsRow[] {
  return filter === "unlinked" ? rows.filter((row) => row.linkedPlayer === null) : rows;
}

/** The link counts: linked, unlinked with a suggestion, unlinked without one. */
export function statsLinkCounts(rows: PlayerStatsRow[]) {
  let linked = 0;
  let suggested = 0;
  for (const row of rows) {
    if (row.linkedPlayer !== null) {
      linked++;
    } else if (row.suggestedPlayer !== null) {
      suggested++;
    }
  }
  return { linked, suggested, unlinked: rows.length - linked - suggested };
}
