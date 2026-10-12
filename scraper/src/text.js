// Text normalization shared by the parsers.

// Java's Character.isISOControl range, which the backend rejects in every string.
const CONTROL_CHARACTER = /[\u0000-\u001f\u007f-\u009f]/;

export function hasControlCharacter(value) {
  return CONTROL_CHARACTER.test(value);
}

/**
 * U+00A0 → space, every whitespace run → one space, trimmed. Punctuation is never touched:
 * `"`, `'`, `.` and `,` are part of team names.
 */
export function normalizeText(value) {
  return value
    .replace(/\u00a0/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

/** A page didn't have the expected shape. `message` is a short, fixed-format detail. */
export class ParseError extends Error {}

/** Shortens a value from the page for a `detail`: normalized, no control characters, ≤ 40 chars. */
export function excerpt(value) {
  const text = normalizeText(String(value)).replace(/[\u0000-\u001f\u007f-\u009f]/g, "?");
  return text.length > 40 ? `${text.slice(0, 40)}…` : text;
}
