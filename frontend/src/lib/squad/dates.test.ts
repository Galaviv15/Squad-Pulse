import { describe, expect, it } from "vitest";
import { formatIsoDate, isValidIsoDate, toIsoDate } from "./dates";

describe("toIsoDate", () => {
  it("writes the local calendar date, padded", () => {
    expect(toIsoDate(new Date(2026, 0, 5, 0, 0))).toBe("2026-01-05");
    expect(toIsoDate(new Date(2026, 11, 31, 23, 59))).toBe("2026-12-31");
  });
});

describe("isValidIsoDate", () => {
  it.each(["2000-02-29", "1998-05-20", "2026-12-31"])("accepts %o", (value) => {
    expect(isValidIsoDate(value)).toBe(true);
  });

  it.each(["", "2001-02-29", "1998-02-30", "1998-13-01", "1998-00-01", "1998-05-00", "98-05-20"])(
    "refuses %o",
    (value) => {
      expect(isValidIsoDate(value)).toBe(false);
    },
  );
});

describe("formatIsoDate", () => {
  it("shows dd.MM.yyyy from the parts", () => {
    expect(formatIsoDate("1998-05-20")).toBe("20.05.1998");
    expect(formatIsoDate("2000-01-01")).toBe("01.01.2000");
  });

  it.each([null, "", "20/05/1998", "1998-05-20T00:00:00Z"])("is null for %o", (value) => {
    expect(formatIsoDate(value)).toBeNull();
  });
});
