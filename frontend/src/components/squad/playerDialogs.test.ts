import { describe, expect, it } from "vitest";
import { ApiError, NetworkError } from "@/lib/api/errors";
import { actionErrorMessage, focusBack } from "./playerDialogs";

const apiError = (status: number, code: string | null = null) =>
  new ApiError(status, { timestamp: "t", status, error: "Error", code, message: "m", details: [] });

describe("actionErrorMessage", () => {
  it.each([
    [apiError(403), "squad.noPermission", null],
    [apiError(404), "squad.player.notFound", "squad"],
    [apiError(409, "STALE_VERSION"), "squad.dialog.stale", "reload"],
    [apiError(409, "PLAYER_ALREADY_RELEASED"), "squad.dialog.alreadyReleased", "reload"],
    [apiError(409, "PLAYER_ALREADY_ACTIVE"), "squad.dialog.alreadyActive", "reload"],
    [apiError(409, "PLAYER_RELEASED"), "squad.form.released", "reload"],
    [apiError(409, "SOMETHING_NEW"), "squad.dialog.failed", null],
    [apiError(409), "squad.dialog.failed", null],
    [apiError(500), "squad.dialog.failed", null],
    [apiError(400), "squad.dialog.failed", null],
    [new NetworkError(new TypeError("Failed to fetch")), "squad.dialog.failed", null],
  ])("maps %s to %s, offering %s", (error, key, offer) => {
    expect(actionErrorMessage(error)).toEqual({ key, offer });
  });
});

describe("focusBack", () => {
  it("returns the opener while it's in the page, else Base UI's default", () => {
    const opener = document.body.appendChild(document.createElement("button"));
    expect(focusBack(opener)()).toBe(opener);
    opener.remove();
    expect(focusBack(opener)()).toBe(true);
    expect(focusBack(null)()).toBe(true);
  });
});
