const LETTER = /\p{L}/u;

/**
 * Up to two initials for an avatar fallback (a club or a person without an image): the first
 * letter of each of the first two words that have one. Words are split on whitespace, and
 * anything that isn't a Unicode letter is skipped (digits, punctuation, emoji), so "מ.ס. דוגמה"
 * gives "מד". Latin is upper-cased; Hebrew has no case, so it's unchanged. "" when the name has no
 * letter at all: the caller shows an icon instead.
 */
export function initials(name: string): string {
  const letters: string[] = [];
  for (const word of name.trim().split(/\s+/)) {
    const letter = LETTER.exec(word)?.[0];
    if (letter !== undefined) {
      letters.push(letter);
    }
    if (letters.length === 2) {
      break;
    }
  }
  return letters.join("").toUpperCase();
}
