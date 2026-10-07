import { describe, expect, it } from "vitest";
import { parseSquadView, squadPageSearchParams } from "./view";

const parse = (query: string) => parseSquadView(new URLSearchParams(query));

describe("parseSquadView", () => {
  it("is the list without a view parameter", () => {
    expect(parse("")).toBe("list");
    expect(parse("status=released&position=CB")).toBe("list");
  });

  it("reads view=cards, wherever it is", () => {
    expect(parse("view=cards")).toBe("cards");
    expect(parse("view=cards&status=all")).toBe("cards");
  });

  it.each([
    ["the default spelled out", "view=list"],
    ["an unknown value", "view=table"],
    ["the wrong case", "view=CARDS"],
    ["an empty value", "view="],
    ["a repeated parameter, even of cards", "view=cards&view=cards"],
    ["a repeated parameter, cards first", "view=cards&view=list"],
  ])("treats %s as the list", (_, query) => {
    expect(parse(query)).toBe("list");
  });
});

describe("squadPageSearchParams", () => {
  it("leaves the default list out", () => {
    expect(squadPageSearchParams({ status: "active" }, "list").toString()).toBe("");
    expect(squadPageSearchParams({ status: "all", position: "CB" }, "list").toString()).toBe(
      "status=all&position=CB",
    );
  });

  it("puts the cards view after the filters, in their fixed order", () => {
    expect(squadPageSearchParams({ status: "active" }, "cards").toString()).toBe("view=cards");
    expect(
      squadPageSearchParams(
        { status: "released", preferredFoot: "LEFT", minAge: 20, position: "GK" },
        "cards",
      ).toString(),
    ).toBe("status=released&position=GK&minAge=20&preferredFoot=LEFT&view=cards");
  });

  it("round-trips through parseSquadView", () => {
    expect(parseSquadView(squadPageSearchParams({ status: "active" }, "cards"))).toBe("cards");
    expect(parseSquadView(squadPageSearchParams({ status: "active" }, "list"))).toBe("list");
  });
});
