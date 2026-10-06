import type { QueryClient } from "@tanstack/react-query";
import type { AuthSession } from "@/lib/api/session";

/**
 * Empties the Query cache whenever the session ends, however it ended (logout, a refused refresh,
 * a logout in another tab), so nothing of one user's data survives into the next login. Every way
 * back to the login form goes through "unauthenticated", so a login always starts from an empty
 * cache. Returns the function that unbinds.
 */
export function bindSessionToQueryClient(session: AuthSession, queryClient: QueryClient) {
  return session.subscribe((status) => {
    if (status === "unauthenticated") {
      queryClient.clear();
    }
  });
}
