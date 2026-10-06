import { describe, expect, it } from "vitest";
import { deletedPlayerNameFromState, deletedPlayerState } from "./routeState";

describe("the deleted-player route state", () => {
  it("carries the name", () => {
    expect(deletedPlayerNameFromState(deletedPlayerState("דני לוי"))).toBe("דני לוי");
  });

  it.each([null, undefined, "x", 1, {}, { deletedPlayerName: 5 }, { other: "a" }])(
    "reads %j as no notice",
    (state) => {
      expect(deletedPlayerNameFromState(state)).toBeNull();
    },
  );
});
