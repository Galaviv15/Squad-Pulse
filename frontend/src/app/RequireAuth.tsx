import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import { Navigate, Outlet, useLocation } from "react-router";
import { LoadingScreen, ServerErrorScreen } from "@/components/auth/StatusScreens";
import { authSession, useSessionStatus } from "@/lib/api/session";
import { CurrentUserContext, currentUserQuery } from "@/lib/auth/currentUser";
import { LogoutContext, type LogoutControl } from "@/lib/auth/logout";
import { LOGIN_PATH, withNext } from "@/lib/auth/paths";

/**
 * The parent of every protected route (the app shell becomes its layout in KAN-48). Without a
 * session it redirects to login: with `next` = the current location when the session ended by
 * itself (a refused refresh, a logout in another tab), without it after an explicit logout from
 * this tab (useLogout), which this component tells apart because it owns that logout.
 */
export function RequireAuth() {
  const status = useSessionStatus();
  const location = useLocation();
  const [loggingOut, setLoggingOut] = useState(false);

  const logoutControl = useMemo<LogoutControl>(
    () => ({
      pending: loggingOut,
      async logout() {
        if (loggingOut) {
          return;
        }
        // Set before the session ends, so the redirect below already knows it was explicit.
        setLoggingOut(true);
        await authSession.logout();
      },
    }),
    [loggingOut],
  );

  if (status !== "authenticated") {
    const here = location.pathname + location.search + location.hash;
    return <Navigate to={loggingOut ? LOGIN_PATH : withNext(LOGIN_PATH, here)} replace />;
  }
  return (
    <LogoutContext value={logoutControl}>
      <CurrentUserGate />
    </LogoutContext>
  );
}

/**
 * Loads /me and renders the protected screens only once it's there, so they can rely on
 * useCurrentUser. Its own component so the query exists only while there's a session: once the
 * session ends, nothing re-creates the /me query in the cache that was just cleared.
 */
function CurrentUserGate() {
  const { data: user, isError, isFetching, refetch } = useQuery(currentUserQuery);

  if (user) {
    return (
      <CurrentUserContext value={user}>
        <Outlet />
      </CurrentUserContext>
    );
  }
  // A 401 never shows this: a refused refresh ends the session, and RequireAuth redirects.
  if (isError && !isFetching) {
    return <ServerErrorScreen onRetry={() => void refetch()} />;
  }
  return <LoadingScreen />;
}
