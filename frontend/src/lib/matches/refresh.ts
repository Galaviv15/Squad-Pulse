import {
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { apiJson } from "@/lib/api/client";
import { MATCHES_DATA_QUERY_KEY, MATCHES_QUERY_KEY } from "./queries";
import type { RefreshState, RefreshStatus } from "./types";

/** GET /matches/refresh's key: beside the data's, so invalidating the data never refetches it. */
export const REFRESH_STATUS_QUERY_KEY = [...MATCHES_QUERY_KEY, "refresh"] as const;

/** How often the status is polled while a run is RUNNING. */
export const REFRESH_POLL_INTERVAL_MS = 3_000;

/** How often the cooldown's "in X minutes" is re-rendered. */
export const COUNTDOWN_TICK_MS = 15_000;

/**
 * GET /matches/refresh: the club's latest scrape run. Polled every REFRESH_POLL_INTERVAL_MS while
 * it's RUNNING, and not otherwise: a run always ends (the backend turns one that never reports
 * back into FAILED NO_RESULT after its timeout, on read), so the polling always stops.
 */
export const refreshStatusQuery = queryOptions({
  queryKey: REFRESH_STATUS_QUERY_KEY,
  queryFn: ({ signal }) => apiJson<RefreshStatus>("/matches/refresh", { signal }),
  refetchInterval: (query) =>
    query.state.data?.state === "RUNNING" ? REFRESH_POLL_INTERVAL_MS : false,
});

/**
 * The refresh status, for the refresh control only: requested only by a user who may refresh
 * (EDIT_FULL and up), so the component that calls this must be rendered only for them.
 *
 * `endedRun` is the state a run just ended in (SUCCEEDED or FAILED), seen as a change from RUNNING
 * in the query's data (a POST's answer, a poll), never from the POST alone: a double click can
 * answer ALREADY_RUNNING for a trigger that then fails. When a run ends, both data queries are
 * invalidated — on FAILED too: before a season's first round every run fails with PARSE_ERROR
 * although the fixtures were stored, and a run can fail after one part was stored.
 */
export function useRefreshStatus() {
  const queryClient = useQueryClient();
  const status = useQuery(refreshStatusQuery);
  const state = status.data?.state;

  // The state of the previous render, to see a change (React's "previous props" pattern).
  const [previousState, setPreviousState] = useState(state);
  const [endedRun, setEndedRun] = useState<RefreshState | null>(null);
  if (state !== previousState) {
    setPreviousState(state);
    setEndedRun(
      previousState === "RUNNING" && (state === "SUCCEEDED" || state === "FAILED") ? state : null,
    );
  }

  useEffect(() => {
    if (endedRun !== null) {
      void queryClient.invalidateQueries({ queryKey: MATCHES_DATA_QUERY_KEY });
    }
  }, [endedRun, queryClient]);

  return { status, endedRun };
}

/**
 * POST /matches/refresh. Never retried (mutations never are). Every outcome but
 * SCRAPER_UNAVAILABLE is put into the status query's cache, so the control shows it at once and
 * the polling starts on RUNNING; GET stays the truth after that. SCRAPER_UNAVAILABLE changed
 * nothing on the server (no run, no lock), so the cached status stays as it was.
 */
export function useTriggerRefresh() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => apiJson<RefreshStatus>("/matches/refresh", { method: "POST" }),
    onSuccess: (answer) => cacheRefreshAnswer(queryClient, answer),
  });
}

async function cacheRefreshAnswer(queryClient: QueryClient, answer: RefreshStatus) {
  if (answer.outcome === "SCRAPER_UNAVAILABLE") {
    return;
  }
  // A GET sent before the POST could land after it and overwrite RUNNING with the old state,
  // which would stop the polling.
  await queryClient.cancelQueries({ queryKey: REFRESH_STATUS_QUERY_KEY, exact: true });
  // The cache holds what a GET returns: no outcome.
  queryClient.setQueryData<RefreshStatus>(REFRESH_STATUS_QUERY_KEY, { ...answer, outcome: null });
}

/**
 * Date.now(), re-read every `tickMs` while `active`. Starts at the time of mounting: a component
 * that needs a fresh start (a new cooldown) is keyed to remount.
 */
export function useNow(tickMs: number, active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) {
      return;
    }
    const id = setInterval(() => setNow(Date.now()), tickMs);
    return () => clearInterval(id);
  }, [tickMs, active]);
  return now;
}
