import { queryOptions } from "@tanstack/react-query";
import { createContext, useContext } from "react";
import { apiJson } from "@/lib/api/client";
import type { PermissionLevel } from "./permissions";

export type { PermissionLevel } from "./permissions";

/** auth.Title. */
export type Title =
  | "CLUB_MANAGER"
  | "HEAD_COACH"
  | "ASSISTANT_COACH"
  | "GOALKEEPING_COACH"
  | "FITNESS_COACH"
  | "ANALYST";

/** auth.ClubResponse: the caller's club. */
export interface Club {
  id: string;
  name: string;
  hasLogo: boolean;
}

/** auth.CurrentUserResponse: GET /auth/users/me. */
export interface CurrentUser {
  id: string;
  email: string;
  fullName: string;
  title: Title;
  /** The effective level, from the access token: it changes only on the next refresh. */
  permissionLevel: PermissionLevel;
  /** ISO date (yyyy-MM-dd), or null when not given. */
  dateOfBirth: string | null;
  active: boolean;
  hasPhoto: boolean;
  activated: boolean;
  club: Club;
}

export const CURRENT_USER_QUERY_KEY = ["auth", "me"] as const;

/**
 * The /me query. Stale after 5 minutes, so a window focus refetches it at most that often: the
 * name, title or club can change under a running session, and a deactivated user's /me answers
 * 401, which ends the session (apiFetch's refresh is refused). Refetching on every focus would
 * mostly cost requests: the effective permission level only changes on a refresh anyway.
 */
export const currentUserQuery = queryOptions({
  queryKey: CURRENT_USER_QUERY_KEY,
  queryFn: ({ signal }) => apiJson<CurrentUser>("/auth/users/me", { signal }),
  staleTime: 5 * 60 * 1000,
});

export const CurrentUserContext = createContext<CurrentUser | null>(null);

/**
 * The logged-in user. Only below the protected route (RequireAuth), which renders its screens only
 * once /me has loaded; anywhere else it throws, since there is no user to return.
 */
export function useCurrentUser(): CurrentUser {
  const user = useContext(CurrentUserContext);
  if (!user) {
    throw new Error("useCurrentUser must be used below the protected route (RequireAuth)");
  }
  return user;
}
