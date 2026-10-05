import { QueryClient } from "@tanstack/react-query";

/** How many times a failed query is retried (on top of the first attempt). */
const MAX_QUERY_RETRIES = 2;

/**
 * The HTTP status an error carries, if any: duck-typed, any object with a numeric `status`. The
 * API client's errors (src/lib/api/errors.ts) keep that contract: an ApiError always has one, a
 * NetworkError (no response) never does.
 */
function httpStatusOf(error: unknown): number | undefined {
  if (typeof error === "object" && error !== null && "status" in error) {
    const { status } = error;
    return typeof status === "number" ? status : undefined;
  }
  return undefined;
}

/**
 * Whether a failed query is tried again. A 4xx is the request's own fault (401, 403, 404,
 * validation, ...), so repeating it can't help. Anything else (a 5xx, or a network failure with
 * no status) is retried up to MAX_QUERY_RETRIES times. failureCount is 0 after the first failure.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  const status = httpStatusOf(error);
  if (status !== undefined && status >= 400 && status <= 499) {
    return false;
  }
  return failureCount < MAX_QUERY_RETRIES;
}

/** The app's QueryClient. Other defaults (staleTime, refetchOnWindowFocus, ...) are TanStack's. */
export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: shouldRetryQuery,
      },
      mutations: {
        // A mutation changes data, so it is never repeated automatically.
        retry: 0,
      },
    },
  });
}
