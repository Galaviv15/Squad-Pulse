/*
 * Match times are Israel time, always: the games' `date` and `kickoffTime` are Israel-local, so
 * every instant (lastUpdatedAt, finishedAt, nextRefreshAt) is shown in Asia/Jerusalem too, and
 * "today" is Israel's calendar date. Never the browser's zone: a user abroad must see the same
 * times as the games, and a game must not turn "past" at midnight somewhere else. (lib/squad/dates
 * is browser-local on purpose, for dates of birth; don't use it here.)
 */

export const ISRAEL_TIME_ZONE = "Asia/Jerusalem";

const DATE_PARTS = new Intl.DateTimeFormat("en-US", {
  timeZone: ISRAEL_TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

/** The Israel-local parts of `date`, as zero-padded strings. Read by part type, never by position. */
function israelParts(date: Date) {
  const parts: Record<string, string> = {};
  for (const { type, value } of DATE_PARTS.formatToParts(date)) {
    parts[type] = value;
  }
  return parts as Record<"year" | "month" | "day" | "hour" | "minute", string>;
}

/** Israel's calendar date at `now`, as yyyy-MM-dd (comparable with a game's `date` as a string). */
export function israelToday(now: Date = new Date()): string {
  const { year, month, day } = israelParts(now);
  return `${year}-${month}-${day}`;
}

/** An instant (ISO-8601) as Israel-local dd.MM.yyyy HH:mm. */
export function formatInstant(instant: string): string {
  const { year, month, day, hour, minute } = israelParts(new Date(instant));
  return `${day}.${month}.${year} ${hour}:${minute}`;
}

/**
 * Whole minutes from `now` until `instant`, rounded up and at least 1 ("in 1 minute" until it
 * has passed); null once it has passed (or is now).
 */
export function minutesUntil(instant: string, now: number): number | null {
  const remaining = Date.parse(instant) - now;
  return remaining > 0 ? Math.max(1, Math.ceil(remaining / 60_000)) : null;
}

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * The day of the week of a yyyy-MM-dd calendar date, 0 = Sunday. From its parts on a UTC date,
 * so neither the browser's zone nor Israel's matter: a calendar date has one weekday. null for a
 * malformed one.
 */
export function weekdayOf(isoDate: string): number | null {
  const match = ISO_DATE.exec(isoDate);
  if (match === null) {
    return null;
  }
  const [year, month, day] = match.slice(1).map(Number);
  return new Date(Date.UTC(year, month - 1, day)).getUTCDay();
}
