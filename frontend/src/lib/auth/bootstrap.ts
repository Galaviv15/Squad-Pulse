import { authSession } from "@/lib/api/session";

// The app-load refresh, shared by every caller until reset. Module-level on purpose: never React
// state, so StrictMode's double effects or several mounted gates can't send a second refresh.
let bootstrap: Promise<void> | null = null;

/**
 * Decides the session at app load: one /auth/refresh with the cookie, the first time it's called;
 * every later call gets the same promise, settled or not. On success the session is
 * "authenticated"; a 4xx makes it "unauthenticated" (a first visit has no cookie: that's the
 * normal 401, not an error). Both reject or resolve the promise the same way the refresh does, so
 * a caller tells a failure that left the session "unknown" (a 5xx, no response, a malformed 200)
 * by the status, not the error.
 */
export function startSessionBootstrap(): Promise<void> {
  bootstrap ??= authSession.refresh().then(() => undefined);
  return bootstrap;
}

/** Forgets the bootstrap, so the next startSessionBootstrap refreshes again: the retry button. */
export function resetSessionBootstrap(): void {
  bootstrap = null;
}
