import type { AuthSession } from "@/lib/api/session";
import type { SquadFilters } from "./filters";
import { SQUAD_PATH } from "./paths";
import { squadPageSearchParams, type SquadView } from "./view";

/**
 * The last squad page URL seen in this tab (filters + view), for the links that mean "back to
 * where I was": "חזרה לסגל" on the player pages, the add form's "ביטול", the card's delete
 * redirect. The navigation entry points (the sidebar's "סגל", the dashboard's "לטבלת הסגל") stay
 * the bare SQUAD_PATH.
 *
 * In memory only, per tab: a reload starts from the bare path, and nothing goes to any storage.
 * Built here from parsed filters and a view through the page's own normalizer, so only a
 * normalized URL can be remembered, never a raw query string. Forgotten when the session ends
 * (bindSessionToSquadReturnPath), so the next login doesn't start from another user's squad.
 */
let remembered: string | null = null;

/** Records the squad page's current filters and view. Called by useSquadPageQuery only. */
export function rememberSquadPage(filters: SquadFilters, view: SquadView) {
  const search = squadPageSearchParams(filters, view).toString();
  remembered = search === "" ? SQUAD_PATH : `${SQUAD_PATH}?${search}`;
}

/**
 * The squad URL a "back to the squad" link goes to: the last one seen in this tab, or the bare
 * SQUAD_PATH. A plain read: it changes only while the squad page is mounted, and no link using it
 * is rendered on the squad page, so a link never shows a stale value.
 */
export function squadReturnPath(): string {
  return remembered ?? SQUAD_PATH;
}

/** Forgets the remembered URL: on session end, and before every test (src/test/setup.ts). */
export function forgetSquadReturnPath() {
  remembered = null;
}

/**
 * Forgets the remembered squad URL whenever the session ends (logout, a refused refresh, a logout
 * in another tab), as bindSessionToQueryClient empties the cache, so a login as someone else in
 * this tab starts from the bare squad. Returns the function that unbinds.
 */
export function bindSessionToSquadReturnPath(session: AuthSession) {
  return session.subscribe((status) => {
    if (status === "unauthenticated") {
      forgetSquadReturnPath();
    }
  });
}
