import {
  MEDICAL_STATUSES,
  PLAYER_STATUSES,
  POSITIONS,
  PREFERRED_FEET,
  type MedicalStatus,
  type PlayerStatus,
  type Position,
  type PreferredFoot,
} from "./types";

/**
 * The filters of GET /squad/players. Both the page URL and the API query string carry them under
 * the API's own parameter names and spellings, so the two are the same words.
 */
export interface SquadFilters {
  status: PlayerStatus;
  /** Matches the primary position only. */
  position?: Position;
  /** Completed years, inclusive, MIN_AGE–MAX_AGE. */
  minAge?: number;
  maxAge?: number;
  medicalStatus?: MedicalStatus;
  preferredFoot?: PreferredFoot;
}

/** The status the server lists without a `status` parameter; never written to a URL. */
export const DEFAULT_PLAYER_STATUS: PlayerStatus = "active";

/** The age filters' range on the server (@Min(18) @Max(99)). */
export const MIN_AGE = 18;
export const MAX_AGE = 99;

/** The filter bar's filters: all but status. Clearing the filter bar removes these. */
export const FILTER_BAR_KEYS = [
  "position",
  "minAge",
  "maxAge",
  "medicalStatus",
  "preferredFoot",
] as const;

/** Whether any filter-bar filter (not status) is set. */
export function hasFilterBarFilters(filters: SquadFilters): boolean {
  return FILTER_BAR_KEYS.some((key) => filters[key] !== undefined);
}

function oneOf<T extends string>(values: readonly T[], value: string | null): T | undefined {
  return values.find((candidate) => candidate === value);
}

/** An age as the server accepts it: a whole number, digits only, MIN_AGE–MAX_AGE. */
export function parseAge(value: string | null): number | undefined {
  if (value === null || !/^\d{1,3}$/.test(value)) {
    return undefined;
  }
  const age = Number(value);
  return age >= MIN_AGE && age <= MAX_AGE ? age : undefined;
}

/**
 * The filters in `params` (a page URL's or an API query's). Anything the server would refuse
 * with a 400 is dropped silently, so the request built from the result is always valid: unknown
 * keys, unknown values (enums are case-sensitive, status lowercase), ages that aren't whole
 * numbers in range, and both ages when minAge > maxAge. A repeated parameter counts by its first
 * value, as Spring binds it. A missing or invalid status is the default, active.
 */
export function parseSquadFilters(params: URLSearchParams): SquadFilters {
  const filters: SquadFilters = {
    status: oneOf(PLAYER_STATUSES, params.get("status")) ?? DEFAULT_PLAYER_STATUS,
  };
  const position = oneOf(POSITIONS, params.get("position"));
  if (position !== undefined) {
    filters.position = position;
  }
  const minAge = parseAge(params.get("minAge"));
  const maxAge = parseAge(params.get("maxAge"));
  if (minAge === undefined || maxAge === undefined || minAge <= maxAge) {
    if (minAge !== undefined) {
      filters.minAge = minAge;
    }
    if (maxAge !== undefined) {
      filters.maxAge = maxAge;
    }
  }
  const medicalStatus = oneOf(MEDICAL_STATUSES, params.get("medicalStatus"));
  if (medicalStatus !== undefined) {
    filters.medicalStatus = medicalStatus;
  }
  const preferredFoot = oneOf(PREFERRED_FEET, params.get("preferredFoot"));
  if (preferredFoot !== undefined) {
    filters.preferredFoot = preferredFoot;
  }
  return filters;
}

/**
 * The filters as query parameters, in a fixed order, the default status and unset filters left
 * out: the normalized page URL, and the API query (no parameters at all for the default list).
 */
export function squadFiltersToSearchParams(filters: SquadFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.status !== DEFAULT_PLAYER_STATUS) {
    params.set("status", filters.status);
  }
  if (filters.position !== undefined) {
    params.set("position", filters.position);
  }
  if (filters.minAge !== undefined) {
    params.set("minAge", String(filters.minAge));
  }
  if (filters.maxAge !== undefined) {
    params.set("maxAge", String(filters.maxAge));
  }
  if (filters.medicalStatus !== undefined) {
    params.set("medicalStatus", filters.medicalStatus);
  }
  if (filters.preferredFoot !== undefined) {
    params.set("preferredFoot", filters.preferredFoot);
  }
  return params;
}
