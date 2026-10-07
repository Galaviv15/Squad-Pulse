import { useCallback, useEffect, useMemo } from "react";
import { useSearchParams } from "react-router";
import {
  FILTER_BAR_KEYS,
  parseSquadFilters,
  squadFiltersToSearchParams,
  type SquadFilters,
} from "./filters";
import { parseSquadView, squadPageSearchParams, type SquadView } from "./view";

export interface SquadPageQueryControl {
  /** The page URL's filters, valid values only. */
  filters: SquadFilters;
  /** Changes the given filters; an `undefined` value removes that filter. Keeps the view. */
  setFilters(patch: Partial<SquadFilters>): void;
  /** Removes the filter-bar filters, keeping status and the view. */
  clearFilters(): void;
  /** How the list is shown (the table, or the cards). */
  view: SquadView;
  /** Changes the view, keeping the filters. */
  setView(view: SquadView): void;
}

/**
 * The squad page's query string, the one owner of it: the filters (so a reload or a copied link
 * shows the same list) and the view (a page preference, not a filter: never part of the API
 * query, and clearing the filters keeps it). Every change replaces the history entry: Back leaves
 * the page rather than stepping back through filters or views.
 *
 * A URL that isn't in normalized form (an invalid value, an unknown key, the default status or
 * view spelled out, another order) is rewritten to it, also with replace. This is the only place
 * that normalizes the page's URL: a second hook "correcting" it its own way would fight this one
 * (a replace loop, or a lost parameter), so a new page parameter goes into squadPageSearchParams.
 * The filters and view read from the URL are already the normalized ones, so the list never waits
 * for the rewrite.
 */
export function useSquadPageQuery(): SquadPageQueryControl {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseSquadFilters(searchParams), [searchParams]);
  const view = parseSquadView(searchParams);

  const current = searchParams.toString();
  const normalized = squadPageSearchParams(filters, view).toString();
  useEffect(() => {
    if (current !== normalized) {
      setSearchParams(normalized, { replace: true });
    }
  }, [current, normalized, setSearchParams]);

  const setFilters = useCallback(
    (patch: Partial<SquadFilters>) => {
      setSearchParams(
        (previous) => {
          const before = parseSquadFilters(previous);
          const merged = { ...before, ...patch, status: patch.status ?? before.status };
          // Parsed once more, so a patch the server would refuse (minAge > maxAge) is dropped too.
          return squadPageSearchParams(
            parseSquadFilters(squadFiltersToSearchParams(merged)),
            parseSquadView(previous),
          );
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  const clearFilters = useCallback(() => {
    setFilters(Object.fromEntries(FILTER_BAR_KEYS.map((key) => [key, undefined])));
  }, [setFilters]);

  const setView = useCallback(
    (next: SquadView) => {
      setSearchParams((previous) => squadPageSearchParams(parseSquadFilters(previous), next), {
        replace: true,
      });
    },
    [setSearchParams],
  );

  return { filters, setFilters, clearFilters, view, setView };
}
