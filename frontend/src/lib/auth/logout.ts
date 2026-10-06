import { createContext, useContext } from "react";

export interface LogoutControl {
  /**
   * Logs out (AuthSession.logout, which also tells the other tabs) and goes to the login screen
   * without `next`. A second call while one runs does nothing.
   */
  logout(): Promise<void>;
  /** True while the logout runs: disable the button. */
  pending: boolean;
}

export const LogoutContext = createContext<LogoutControl | null>(null);

/**
 * Explicit logout, for the logout button. Only below the protected route (RequireAuth), which
 * owns it: an explicit logout must go to login without `next`, unlike a session that ended by
 * itself, and only the guard that redirects can tell the two apart.
 */
export function useLogout(): LogoutControl {
  const control = useContext(LogoutContext);
  if (!control) {
    throw new Error("useLogout must be used below the protected route (RequireAuth)");
  }
  return control;
}
