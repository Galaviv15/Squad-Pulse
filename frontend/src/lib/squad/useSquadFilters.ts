import { useCallback, useEffect, useMemo } from "react";
import { useSearchParams } from "react-router";
import {
  FILTER_BAR_KEYS,
  parseSquadFilters,
  squadFiltersToSearchParams,
  type SquadFilters,
} from "./filters";

export interface SquadFiltersControl {
  /** The page URL's filters, valid values only. */
  filters: SquadFilters;
  /** Changes the given filters; an `undefined` value removes that filter. */
  setFilters(patch: Partial<SquadFilters>): void;
  /** Removes the filter-bar filters, keeping status. */
  clearFilters(): void;
}

/**
 * The squad filters, kept in the page URL's query string (so a reload or a copied link shows the
 * same list), independent of how the list is shown (the table, or KAN-52's cards). Every change
 * replaces the history entry: Back leaves the page rather than stepping back through filters.
 *
 * A URL that isn't in normalized form (an invalid value, an unknown key, the default status
 * spelled out, another order) is rewritten to it, also with replace. The filters read from it are
 * already the normalized ones, so the list never waits for the rewrite. A page parameter that
 * isn't a filter (e.g. KAN-52's `view`) must be added to that normalization, or it's removed as
 * an unknown key.
 */
export function useSquadFilters(): SquadFiltersControl {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => parseSquadFilters(searchParams), [searchParams]);

  const current = searchParams.toString();
  const normalized = squadFiltersToSearchParams(filters).toString();
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
          return squadFiltersToSearchParams(parseSquadFilters(squadFiltersToSearchParams(merged)));
        },
        { replace: true },
      );
    },
    [setSearchParams],
  );

  const clearFilters = useCallback(() => {
    setFilters(Object.fromEntries(FILTER_BAR_KEYS.map((key) => [key, undefined])));
  }, [setFilters]);

  return { filters, setFilters, clearFilters };
}
