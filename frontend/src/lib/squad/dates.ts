const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** `date`'s local calendar date as yyyy-MM-dd (the form's "today", the date input's max). */
export function toIsoDate(date: Date): string {
  const pad = (value: number, length: number) => String(value).padStart(length, "0");
  return `${pad(date.getFullYear(), 4)}-${pad(date.getMonth() + 1, 2)}-${pad(date.getDate(), 2)}`;
}

/**
 * Whether `value` is a yyyy-MM-dd date that exists in the calendar (not 2001-02-29). Read by its
 * parts, never with new Date("yyyy-MM-dd"), which is UTC midnight.
 */
export function isValidIsoDate(value: string): boolean {
  const match = ISO_DATE.exec(value);
  if (match === null) {
    return false;
  }
  const [year, month, day] = match.slice(1).map(Number);
  const date = new Date(2000, month - 1, day);
  date.setFullYear(year);
  return date.getFullYear() === year && date.getMonth() === month - 1 && date.getDate() === day;
}

/** A yyyy-MM-dd date shown as dd.MM.yyyy, from its parts; null for a missing or malformed one. */
export function formatIsoDate(value: string | null): string | null {
  const match = value === null ? null : ISO_DATE.exec(value);
  return match === null ? null : `${match[3]}.${match[2]}.${match[1]}`;
}
