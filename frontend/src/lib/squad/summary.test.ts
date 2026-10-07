import { describe, expect, it } from "vitest";
import { formatAverageAge, SQUAD_SUMMARY_QUERY_KEY } from "./summary";

describe("formatAverageAge", () => {
  it("shows a dash without an average", () => {
    expect(formatAverageAge(null)).toBe("—");
  });

  // The server sends 25.0 as the JSON number 25.
  it.each([
    [25, "25.0"],
    [25.8, "25.8"],
    [0, "0.0"],
    [100.5, "100.5"],
  ])("shows %s with exactly one decimal: %s", (age, shown) => {
    expect(formatAverageAge(age)).toBe(shown);
  });

  // Never sent (the server rounds to one decimal), but toFixed rounds what it's given.
  it("rounds a longer value to one decimal", () => {
    expect(formatAverageAge(25.84)).toBe("25.8");
    expect(formatAverageAge(30.05)).toBe("30.1");
  });

  it("writes ASCII digits and a dot, whatever the locale", () => {
    expect(formatAverageAge(26.4)).toMatch(/^\d+\.\d$/);
  });
});

describe("SQUAD_SUMMARY_QUERY_KEY", () => {
  // Under ["squad"], which every player write invalidates.
  it('is ["squad", "summary"]', () => {
    expect(SQUAD_SUMMARY_QUERY_KEY).toEqual(["squad", "summary"]);
  });
});
