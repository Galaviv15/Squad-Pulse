import type { Line, MedicalStatus, PlayerStatus, PreferredFoot } from "./types";

/*
 * The squad enums' i18n keys. Records over the full unions, so a new backend value is a compile
 * error until it has a Hebrew label. Positions have none: they're shown as-is, in English.
 */

export const MEDICAL_STATUS_KEYS: Record<MedicalStatus, string> = {
  FIT: "squad.medical.FIT",
  INJURED: "squad.medical.INJURED",
};

export const PREFERRED_FOOT_KEYS: Record<PreferredFoot, string> = {
  RIGHT: "squad.foot.RIGHT",
  LEFT: "squad.foot.LEFT",
  BOTH: "squad.foot.BOTH",
};

/** The status control's options. */
export const PLAYER_STATUS_KEYS: Record<PlayerStatus, string> = {
  active: "squad.status.active",
  released: "squad.status.released",
  all: "squad.status.all",
};

/** The empty list's text when no filter-bar filter is set, by status. */
export const EMPTY_SQUAD_KEYS: Record<PlayerStatus, string> = {
  active: "squad.empty.active",
  released: "squad.empty.released",
  all: "squad.empty.all",
};

/** The dashboard's line names (legend, segment titles), in LINES order. */
export const LINE_KEYS: Record<Line, string> = {
  GOALKEEPERS: "dashboard.lines.GOALKEEPERS",
  DEFENSE: "dashboard.lines.DEFENSE",
  MIDFIELD: "dashboard.lines.MIDFIELD",
  ATTACK: "dashboard.lines.ATTACK",
};
