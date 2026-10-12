import { describe, expect, it } from "vitest";
import { parseMatchesTab } from "./tab";

describe("parseMatchesTab", () => {
  it("is the table only for a single tab=table", () => {
    expect(parseMatchesTab(new URLSearchParams("tab=table"))).toBe("table");
  });

  it.each(["", "tab=games", "tab=TABLE", "tab=standings", "tab=table&tab=table", "tab="])(
    "is the games for %j",
    (query) => {
      expect(parseMatchesTab(new URLSearchParams(query))).toBe("games");
    },
  );
});
