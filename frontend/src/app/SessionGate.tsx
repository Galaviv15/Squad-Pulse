import { useEffect, useState } from "react";
import { Outlet } from "react-router";
import { LoadingScreen, ServerErrorScreen } from "@/components/auth/StatusScreens";
import { authSession, useSessionStatus } from "@/lib/api/session";
import { resetSessionBootstrap, startSessionBootstrap } from "@/lib/auth/bootstrap";

/**
 * The /app route's element: renders nothing of /app, public or protected, until the session is
 * decided, so neither the login form nor app content can flash. While the status is "unknown" it
 * runs the app-load bootstrap (one shared refresh, src/lib/auth/bootstrap.ts) and shows the loading
 * screen; if that left the status "unknown" (5xx, no response, a malformed answer) it shows the
 * server-error screen with a retry, never the login form: the cookie may be perfectly fine.
 */
export function SessionGate() {
  const status = useSessionStatus();
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (status !== "unknown" || failed) {
      return;
    }
    let current = true;
    startSessionBootstrap().catch(() => {
      // A 4xx has already made the session "unauthenticated", which the status re-renders.
      if (current && authSession.getStatus() === "unknown") {
        setFailed(true);
      }
    });
    return () => {
      current = false;
    };
  }, [status, failed]);

  if (status !== "unknown") {
    return <Outlet />;
  }
  if (failed) {
    return (
      <ServerErrorScreen
        onRetry={() => {
          resetSessionBootstrap();
          setFailed(false);
        }}
      />
    );
  }
  return <LoadingScreen />;
}
