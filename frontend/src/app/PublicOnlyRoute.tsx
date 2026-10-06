import { Navigate, Outlet, useSearchParams } from "react-router";
import { useSessionStatus } from "@/lib/api/session";
import { NEXT_PARAM, safeNextPath } from "@/lib/auth/paths";

/**
 * The parent of the public auth routes (login, forgot / reset password). A logged-in user is sent
 * on to the validated `next` (or /app), replacing the auth screen in the history, so Back doesn't
 * return to it. This is also how a login, or the login after a password reset, leaves the form:
 * the session becomes "authenticated" and this redirects.
 */
export function PublicOnlyRoute() {
  const status = useSessionStatus();
  const [searchParams] = useSearchParams();

  if (status === "authenticated") {
    return <Navigate to={safeNextPath(searchParams.get(NEXT_PARAM))} replace />;
  }
  return <Outlet />;
}
