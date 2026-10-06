import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useMatches } from "react-router";

/**
 * A route's `handle` in the app's route table. React Router types `handle` as `any` on a route
 * and `unknown` on a match, so routes declare theirs with `satisfies RouteHandle` and the shell
 * reads it back through isRouteHandle.
 */
export interface RouteHandle {
  /** The i18n key of the page's title: the shell's <h1> and the browser tab's title. */
  titleKey?: string;
}

function isRouteHandle(handle: unknown): handle is RouteHandle {
  return typeof handle === "object" && handle !== null;
}

/** The current page's title, from the deepest matched route that declares one; null if none does. */
export function usePageTitle(): string | null {
  const { t } = useTranslation();
  const matches = useMatches();
  for (let i = matches.length - 1; i >= 0; i--) {
    const { handle } = matches[i];
    if (isRouteHandle(handle) && handle.titleKey !== undefined) {
      return t(handle.titleKey);
    }
  }
  return null;
}

/**
 * Sets the browser tab's title to "<page title> · SquadPulse" (plain "SquadPulse" without a page
 * title), and back to plain "SquadPulse" on unmount, so the screens outside the shell (login) never
 * keep a page's title.
 */
export function useDocumentTitle(pageTitle: string | null) {
  const { t } = useTranslation();
  const appName = t("app.name");

  useEffect(() => {
    document.title = pageTitle === null ? appName : `${pageTitle} · ${appName}`;
    return () => {
      document.title = appName;
    };
  }, [pageTitle, appName]);
}
