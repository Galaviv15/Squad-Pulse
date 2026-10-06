import { describe, expect, it } from "vitest";
import { ageOn } from "./age";

/** A local date (months 1-12), at a given hour: ageOn reads the local calendar date. */
const day = (year: number, month: number, date: number, hour = 12) =>
  new Date(year, month - 1, date, hour);

describe("ageOn", () => {
  it("counts the year on the birthday itself", () => {
    expect(ageOn("2000-05-20", day(2026, 5, 20))).toBe(26);
  });

  it("doesn't count it the day before", () => {
    expect(ageOn("2000-05-20", day(2026, 5, 19))).toBe(25);
  });

  it("counts it from the first minute of the birthday, and until the last", () => {
    expect(ageOn("2000-05-20", new Date(2026, 4, 20, 0, 0))).toBe(26);
    expect(ageOn("2000-05-20", new Date(2026, 4, 19, 23, 59))).toBe(25);
  });

  it("counts a birthday later in the year only once it's reached", () => {
    expect(ageOn("2000-12-31", day(2026, 12, 30))).toBe(25);
    expect(ageOn("2000-12-31", day(2026, 12, 31))).toBe(26);
    expect(ageOn("2001-01-01", day(2026, 12, 31))).toBe(25);
    expect(ageOn("2001-01-01", day(2027, 1, 1))).toBe(26);
  });

  describe("born on 29 February (as Period.getYears() counts it)", () => {
    it("isn't a year older on 28 February of a non-leap year", () => {
      expect(ageOn("2000-02-29", day(2027, 2, 28))).toBe(26);
    });

    it("is on 1 March of a non-leap year", () => {
      expect(ageOn("2000-02-29", day(2027, 3, 1))).toBe(27);
    });

    it("is on 29 February of a leap year", () => {
      expect(ageOn("2000-02-29", day(2028, 2, 28))).toBe(27);
      expect(ageOn("2000-02-29", day(2028, 2, 29))).toBe(28);
    });
  });

  it("reads the date by its parts, not as UTC midnight", () => {
    // new Date("2000-05-20") would be 19 May in any zone west of UTC.
    expect(ageOn("2000-05-20", new Date(2026, 4, 20, 0, 0, 1))).toBe(26);
  });

  it.each([null, "", "20-05-2000", "2000-5-20", "2000/05/20", "2000-05-20T00:00:00Z"])(
    "is null for %o",
    (dateOfBirth) => {
      expect(ageOn(dateOfBirth, day(2026, 1, 1))).toBeNull();
    },
  );
});
