import { useSearchParams } from "react-router";

/** The matches screen. */
export const MATCHES_PATH = "/app/matches";

/** The matches screen's tabs: the games (default) and the league table. */
export const MATCHES_TABS = ["games", "table"] as const;
export type MatchesTab = (typeof MATCHES_TABS)[number];

/** The tab without a `tab` parameter; never written to a URL. */
export const DEFAULT_MATCHES_TAB: MatchesTab = "games";

/** The matches screen opened on the league table (the dashboard's table card links here). */
export const MATCHES_TABLE_PATH = `${MATCHES_PATH}?tab=table`;

/**
 * The tab in a matches screen URL's `params`, like the squad's view: only a single `tab=table` is
 * the table; anything else (no `tab`, another value or case, a repeated `tab`) the games.
 */
export function parseMatchesTab(params: URLSearchParams): MatchesTab {
  const values = params.getAll("tab");
  return values.length === 1 && values[0] === "table" ? "table" : DEFAULT_MATCHES_TAB;
}

/**
 * The matches screen's tab, kept in the URL (`?tab=table`, nothing for the games), so a link can
 * open the table and a reload or Back keeps it. A switch replaces the entry, as the squad's view
 * toggle does: switching tabs isn't a navigation.
 */
export function useMatchesTab() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = parseMatchesTab(searchParams);
  const setTab = (next: MatchesTab) => {
    setSearchParams(next === DEFAULT_MATCHES_TAB ? {} : { tab: next }, { replace: true });
  };
  return { tab, setTab };
}
