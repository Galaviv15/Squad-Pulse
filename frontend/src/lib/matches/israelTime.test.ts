import { describe, expect, it } from "vitest";
import { formatInstant, israelToday, minutesUntil, weekdayOf } from "./israelTime";

describe("israelToday", () => {
  it("is Israel's date when UTC is still on the day before (23:30 UTC, summer: +3)", () => {
    // 23:30 UTC on 11 Oct is 02:30 on 12 Oct in Israel (IDT, UTC+3).
    expect(israelToday(new Date("2026-10-11T23:30:00Z"))).toBe("2026-10-12");
  });

  it("turns at midnight Israel time, not UTC midnight", () => {
    // 21:30 UTC on 11 Oct is 00:30 on 12 Oct in Israel; 20:59 UTC is still 23:59 on 11 Oct.
    expect(israelToday(new Date("2026-10-11T21:30:00Z"))).toBe("2026-10-12");
    expect(israelToday(new Date("2026-10-11T20:59:00Z"))).toBe("2026-10-11");
  });

  it("is not the browser's local date (the test runs in whatever zone the machine has)", () => {
    // 22:30 UTC on 31 Dec: 00:30 on 1 Jan in Israel (IST, UTC+2), whatever the local zone says.
    expect(israelToday(new Date("2026-12-31T22:30:00Z"))).toBe("2027-01-01");
    expect(israelToday(new Date("2026-12-31T21:59:00Z"))).toBe("2026-12-31");
  });
});

describe("formatInstant", () => {
  it("shows an instant in Israel time as dd.MM.yyyy HH:mm", () => {
    expect(formatInstant("2026-10-12T15:30:00Z")).toBe("12.10.2026 18:30");
    expect(formatInstant("2026-01-05T08:05:00Z")).toBe("05.01.2026 10:05");
  });

  it("follows Israel's DST change on the last Sunday of October (2026-10-25, 02:00 IDT → 01:00 IST)", () => {
    expect(formatInstant("2026-10-24T22:59:00Z")).toBe("25.10.2026 01:59");
    expect(formatInstant("2026-10-24T23:00:00Z")).toBe("25.10.2026 01:00");
    expect(formatInstant("2026-10-25T12:00:00Z")).toBe("25.10.2026 14:00");
  });

  it("shows midnight as 00:00, never 24:00", () => {
    expect(formatInstant("2026-10-11T21:00:00Z")).toBe("12.10.2026 00:00");
  });

  it("reads an instant with milliseconds", () => {
    expect(formatInstant("2026-10-12T15:30:59.987Z")).toBe("12.10.2026 18:30");
  });
});

describe("minutesUntil", () => {
  const now = Date.parse("2026-10-12T15:00:00Z");

  it("rounds up to whole minutes", () => {
    expect(minutesUntil("2026-10-12T15:10:00Z", now)).toBe(10);
    expect(minutesUntil("2026-10-12T15:09:01Z", now)).toBe(10);
    expect(minutesUntil("2026-10-12T15:01:00.001Z", now)).toBe(2);
  });

  it("is at least 1 while the time hasn't passed", () => {
    expect(minutesUntil("2026-10-12T15:00:01Z", now)).toBe(1);
    expect(minutesUntil("2026-10-12T15:00:00.001Z", now)).toBe(1);
  });

  it("is null once it has passed, or is now", () => {
    expect(minutesUntil("2026-10-12T15:00:00Z", now)).toBeNull();
    expect(minutesUntil("2026-10-12T14:59:00Z", now)).toBeNull();
  });
});

describe("weekdayOf", () => {
  it("is the calendar date's weekday, 0 = Sunday", () => {
    expect(weekdayOf("2026-10-11")).toBe(0);
    expect(weekdayOf("2026-10-17")).toBe(6);
    expect(weekdayOf("2028-02-29")).toBe(2);
  });

  it("is null for a malformed date", () => {
    expect(weekdayOf("17/10/2026")).toBeNull();
  });
});
