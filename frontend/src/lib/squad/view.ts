import { squadFiltersToSearchParams, type SquadFilters } from "./filters";

/** How the squad page shows the list: the table, or a grid of player cards. */
export const SQUAD_VIEWS = ["list", "cards"] as const;
export type SquadView = (typeof SQUAD_VIEWS)[number];

/** The view without a `view` parameter; never written to a URL. */
export const DEFAULT_SQUAD_VIEW: SquadView = "list";

/**
 * The view in a squad page URL's `params`. Only a single `view=cards` is the cards; anything else
 * (no `view`, another value or case, a repeated `view`) is the default list.
 */
export function parseSquadView(params: URLSearchParams): SquadView {
  const values = params.getAll("view");
  return values.length === 1 && values[0] === "cards" ? "cards" : DEFAULT_SQUAD_VIEW;
}

/**
 * The squad page's normalized query string: the filters in their fixed order, then `view`, left
 * out for the default list. The view is a page parameter, never part of the API query.
 */
export function squadPageSearchParams(filters: SquadFilters, view: SquadView): URLSearchParams {
  const params = squadFiltersToSearchParams(filters);
  if (view !== DEFAULT_SQUAD_VIEW) {
    params.set("view", view);
  }
  return params;
}
