/**
 * The squad API's types, mirroring the backend (squad.PlayerResponse and its enums). Each enum is
 * a value array in the backend's declaration order plus the union derived from it: the arrays
 * drive the filter options and the URL validation, so a value can't be in one and not the other.
 */

/** squad.Position, in enum order (GK → ST), the order the server sorts by. Shown as-is, in English. */
export const POSITIONS = ["GK", "CB", "RB", "LB", "DM", "CM", "AM", "RW", "LW", "ST"] as const;
export type Position = (typeof POSITIONS)[number];

/** squad.MedicalStatus. */
export const MEDICAL_STATUSES = ["FIT", "INJURED"] as const;
export type MedicalStatus = (typeof MEDICAL_STATUSES)[number];

/** squad.PreferredFoot. */
export const PREFERRED_FEET = ["RIGHT", "LEFT", "BOTH"] as const;
export type PreferredFoot = (typeof PREFERRED_FEET)[number];

/** squad.PlayerStatus: the list's `status` filter, lowercase as in the query string. */
export const PLAYER_STATUSES = ["active", "released", "all"] as const;
export type PlayerStatus = (typeof PLAYER_STATUSES)[number];

/**
 * squad.PlayerResponse: a player of GET /squad/players. There is no age: it's computed on the
 * client from dateOfBirth (ageOn). dateOfBirth and primaryPosition are required by the API, but
 * data stored another way can lack them, so they are nullable here too.
 */
export interface Player {
  id: string;
  fullName: string;
  primaryPosition: Position | null;
  secondaryPosition: Position | null;
  jerseyNumber: number | null;
  /** ISO date (yyyy-MM-dd). */
  dateOfBirth: string | null;
  heightCm: number | null;
  weightKg: number | null;
  preferredFoot: PreferredFoot | null;
  medicalStatus: MedicalStatus;
  /** false for a released player. */
  active: boolean;
  /** Sent back on an edit, release or re-activation (optimistic locking). */
  version: number;
  createdAt: string;
  updatedAt: string;
  /** Whether GET /squad/players/{id}/photo has a photo: fetch it only when true. */
  hasPhoto: boolean;
}

/**
 * squad.Line: the line of the team a primary position belongs to, from the goal outwards (the
 * summary's order, and the dashboard legend's and donut's).
 */
export const LINES = ["GOALKEEPERS", "DEFENSE", "MIDFIELD", "ATTACK"] as const;
export type Line = (typeof LINES)[number];

/**
 * squad.SquadSummaryResponse (GET /squad/summary): the club's active players only, whatever their
 * medical status. playerCount can exceed the sum of `lines`: a player without a primary position
 * (only in data not written through the API) is counted but in no line.
 */
export interface SquadSummary {
  playerCount: number;
  /**
   * The mean exact age, rounded to one decimal by the server, but a JSON number: 26.0 arrives as
   * 26 (format it with formatAverageAge). null when no active player has a date of birth.
   */
  averageAge: number | null;
  /** Active players per line by primary position: every line, 0 for an empty one. */
  lines: Record<Line, number>;
}
