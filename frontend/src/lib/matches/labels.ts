import type {
  AppearanceRole,
  DetailsState,
  FailureReason,
  GameEventKind,
  Result,
  Side,
} from "./types";
import type { MatchesTab } from "./tab";

/*
 * The matches enums' i18n keys. Records over the full unions, so a new backend value is a compile
 * error until it has a Hebrew label: no raw enum value is ever shown.
 */

export const SIDE_KEYS: Record<Side, string> = {
  HOME: "matches.side.HOME",
  AWAY: "matches.side.AWAY",
};

export const RESULT_KEYS: Record<Result, string> = {
  WIN: "matches.result.WIN",
  DRAW: "matches.result.DRAW",
  LOSS: "matches.result.LOSS",
};

/** A plain sentence per failure: what happened, never the internals. */
export const FAILURE_REASON_KEYS: Record<FailureReason, string> = {
  SOURCE_BLOCKED: "matches.refresh.failure.SOURCE_BLOCKED",
  SOURCE_TIMEOUT: "matches.refresh.failure.SOURCE_TIMEOUT",
  SOURCE_ERROR: "matches.refresh.failure.SOURCE_ERROR",
  PARSE_ERROR: "matches.refresh.failure.PARSE_ERROR",
  REJECTED: "matches.refresh.failure.REJECTED",
  NO_RESULT: "matches.refresh.failure.NO_RESULT",
};

export const MATCHES_TAB_KEYS: Record<MatchesTab, string> = {
  games: "matches.tabs.games",
  table: "matches.tabs.table",
};

/**
 * The weekday names, 0 = Sunday (weekdayOf), the dashboard's weekly-schedule keys: the UI's own
 * wording, not Intl's.
 */
export const WEEKDAY_KEYS = [
  "dashboard.weekly.days.sunday",
  "dashboard.weekly.days.monday",
  "dashboard.weekly.days.tuesday",
  "dashboard.weekly.days.wednesday",
  "dashboard.weekly.days.thursday",
  "dashboard.weekly.days.friday",
  "dashboard.weekly.days.saturday",
] as const;

/** The lineup's groups (KAN-81), in the order they're shown. */
export const APPEARANCE_ROLE_KEYS: Record<AppearanceRole, string> = {
  STARTER: "matches.game.role.STARTER",
  SUBSTITUTE: "matches.game.role.SUBSTITUTE",
  UNUSED: "matches.game.role.UNUSED",
};

/** A game event's name: the legend's label and the start of its screen-reader text. */
export const GAME_EVENT_KEYS: Record<GameEventKind, string> = {
  GOAL: "matches.game.event.GOAL",
  YELLOW: "matches.game.event.YELLOW",
  SECOND_YELLOW: "matches.game.event.SECOND_YELLOW",
  RED: "matches.game.event.RED",
  SUB_ON: "matches.game.event.SUB_ON",
  SUB_OFF: "matches.game.event.SUB_OFF",
};

/** The muted text of a played game whose details can't be opened (yet). */
export const DETAILS_STATE_TEXT_KEYS: Record<Exclude<DetailsState, "AVAILABLE">, string> = {
  PENDING: "matches.details.PENDING",
  UNAVAILABLE: "matches.details.UNAVAILABLE",
};
