import { describe, expect, it } from "vitest";
import { initials } from "./initials";

describe("initials", () => {
  it.each([
    ["two Hebrew words", "דנה כהן", "דכ"],
    ["three words: the first two count", "הפועל רמת גן", "הר"],
    ["one word", "ביתר", "ב"],
    ["Latin, upper-cased", "dana rosen", "DR"],
    ["punctuation inside a word", "מ.ס. דוגמה", "מד"],
    ["leading and trailing spaces", "   Test   FC  ", "TF"],
    ["a mixed Hebrew and Latin name", "דנה Rosen", "דR"],
    ["a word without letters is skipped", "1. FC Köln", "FK"],
    ["a word starting with a digit", "4ever United", "EU"],
  ])("takes the first letters for %s", (_case, name, expected) => {
    expect(initials(name)).toBe(expected);
  });

  it.each([
    ["empty", ""],
    ["whitespace only", "   "],
    ["digits only", "1948 2024"],
    ["punctuation only", "-- !!"],
    ["emoji only", "⚽ 🏆"],
  ])("returns nothing usable for %s", (_case, name) => {
    expect(initials(name)).toBe("");
  });
});
