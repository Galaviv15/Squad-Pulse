import { queryOptions, useQuery } from "@tanstack/react-query";
import { apiJson } from "@/lib/api/client";
import { SQUAD_QUERY_KEY } from "./players";
import type { SquadSummary } from "./types";

/**
 * The squad summary's query key. Under SQUAD_QUERY_KEY, so every player write (which invalidates
 * that prefix) refetches it too, with no code of its own.
 */
export const SQUAD_SUMMARY_QUERY_KEY = [...SQUAD_QUERY_KEY, "summary"] as const;

/**
 * GET /squad/summary: the dashboard's KPIs. TanStack's defaults (staleTime 0): coming back to the
 * dashboard or to the window shows the cached summary at once and refetches it in the background,
 * so it's never older than the last visit.
 */
export const squadSummaryQuery = queryOptions({
  queryKey: SQUAD_SUMMARY_QUERY_KEY,
  queryFn: ({ signal }) => apiJson<SquadSummary>("/squad/summary", { signal }),
});

export function useSquadSummary() {
  return useQuery(squadSummaryQuery);
}

/**
 * The average age as shown: exactly one decimal ("25.0", "25.8"), or "—" without one. toFixed,
 * not Intl.NumberFormat: it always writes ASCII digits and a "." whatever the locale, and the
 * server already rounded to one decimal (half up), so toFixed's own rounding never applies to a
 * value it sends.
 */
export function formatAverageAge(averageAge: number | null): string {
  return averageAge === null ? "—" : averageAge.toFixed(1);
}
