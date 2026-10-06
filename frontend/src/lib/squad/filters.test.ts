import { describe, expect, it } from "vitest";
import {
  hasFilterBarFilters,
  parseSquadFilters,
  squadFiltersToSearchParams,
  type SquadFilters,
} from "./filters";
import { squadPlayersPath } from "./players";

const parse = (query: string) => parseSquadFilters(new URLSearchParams(query));
const serialize = (filters: SquadFilters) => squadFiltersToSearchParams(filters).toString();

describe("parseSquadFilters", () => {
  it("defaults to the active players, with no filter", () => {
    expect(parse("")).toEqual({ status: "active" });
  });

  it("reads every filter", () => {
    expect(
      parse(
        "status=released&position=CB&minAge=20&maxAge=30&medicalStatus=INJURED&preferredFoot=LEFT",
      ),
    ).toEqual({
      status: "released",
      position: "CB",
      minAge: 20,
      maxAge: 30,
      medicalStatus: "INJURED",
      preferredFoot: "LEFT",
    });
  });

  it.each(["active", "released", "all"] as const)("reads status=%s", (status) => {
    expect(parse(`status=${status}`)).toEqual({ status });
  });

  it.each([
    ["an unknown status", "status=gone"],
    ["a status in the wrong case", "status=ACTIVE"],
    ["an empty status", "status="],
    ["an unknown position", "position=XX"],
    ["a position in the wrong case", "position=cb"],
    ["an unknown medical status", "medicalStatus=SICK"],
    ["a medical status in the wrong case", "medicalStatus=fit"],
    ["an unknown foot", "preferredFoot=NONE"],
    ["a foot in the wrong case", "preferredFoot=Right"],
    ["an unknown key", "foo=1&view=cards"],
    ["an age below 18", "minAge=17"],
    ["an age above 99", "maxAge=100"],
    ["a fractional age", "minAge=20.5"],
    ["a negative age", "minAge=-20"],
    ["a signed age", "minAge=%2B20"],
    ["an age with spaces", "minAge=%2020"],
    ["a non-numeric age", "maxAge=abc"],
    ["an exponent", "maxAge=2e1"],
    ["an empty age", "minAge=&maxAge="],
    ["an age that's too long", "minAge=0000020"],
  ])("drops %s", (_, query) => {
    expect(parse(query)).toEqual({ status: "active" });
  });

  it("drops both ages when minAge > maxAge", () => {
    expect(parse("minAge=30&maxAge=20&position=GK")).toEqual({ status: "active", position: "GK" });
  });

  it("keeps an age range of one year", () => {
    expect(parse("minAge=25&maxAge=25")).toEqual({ status: "active", minAge: 25, maxAge: 25 });
  });

  it("keeps the valid age when only the other is invalid", () => {
    expect(parse("minAge=30&maxAge=10")).toEqual({ status: "active", minAge: 30 });
  });

  it("accepts the bounds 18 and 99", () => {
    expect(parse("minAge=18&maxAge=99")).toEqual({ status: "active", minAge: 18, maxAge: 99 });
  });

  it("reads a repeated parameter by its first value", () => {
    expect(parse("position=CB&position=GK&minAge=20&minAge=40")).toEqual({
      status: "active",
      position: "CB",
      minAge: 20,
    });
    expect(parse("position=XX&position=GK")).toEqual({ status: "active" });
  });

  it("keeps the valid filters of a partly invalid query", () => {
    expect(parse("position=XX&minAge=10&foo=1&status=ACTIVE&medicalStatus=FIT")).toEqual({
      status: "active",
      medicalStatus: "FIT",
    });
  });
});

describe("squadFiltersToSearchParams", () => {
  it("leaves out the default status and every unset filter", () => {
    expect(serialize({ status: "active" })).toBe("");
  });

  it.each(["released", "all"] as const)("writes status=%s", (status) => {
    expect(serialize({ status })).toBe(`status=${status}`);
  });

  it("writes every filter, in a fixed order", () => {
    expect(
      serialize({
        preferredFoot: "BOTH",
        medicalStatus: "FIT",
        maxAge: 30,
        minAge: 20,
        position: "ST",
        status: "all",
      }),
    ).toBe("status=all&position=ST&minAge=20&maxAge=30&medicalStatus=FIT&preferredFoot=BOTH");
  });

  it("round-trips through parseSquadFilters", () => {
    const filters: SquadFilters[] = [
      { status: "active" },
      { status: "released", position: "GK" },
      { status: "all", minAge: 18, maxAge: 99, medicalStatus: "INJURED", preferredFoot: "LEFT" },
      { status: "active", maxAge: 25 },
    ];
    for (const filter of filters) {
      expect(parseSquadFilters(squadFiltersToSearchParams(filter))).toEqual(filter);
    }
  });

  it("normalizes a query: valid values kept, in order, the rest dropped", () => {
    expect(serialize(parse("foo=1&maxAge=30&status=active&position=CB&minAge=020"))).toBe(
      "position=CB&minAge=20&maxAge=30",
    );
  });
});

describe("squadPlayersPath", () => {
  it("has no query string for the default list", () => {
    expect(squadPlayersPath({ status: "active" })).toBe("/squad/players");
  });

  it("carries the filters under the API's names", () => {
    expect(squadPlayersPath({ status: "released", position: "CB", minAge: 20 })).toBe(
      "/squad/players?status=released&position=CB&minAge=20",
    );
  });
});

describe("hasFilterBarFilters", () => {
  it("ignores status", () => {
    expect(hasFilterBarFilters({ status: "released" })).toBe(false);
  });

  it.each([
    { position: "GK" },
    { minAge: 20 },
    { maxAge: 30 },
    { medicalStatus: "FIT" },
    { preferredFoot: "BOTH" },
  ] as Partial<SquadFilters>[])("is true with %o", (filter) => {
    expect(hasFilterBarFilters({ status: "active", ...filter })).toBe(true);
  });
});
