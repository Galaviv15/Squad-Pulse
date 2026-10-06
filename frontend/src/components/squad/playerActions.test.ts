import { describe, expect, it } from "vitest";
import type { PermissionLevel } from "@/lib/auth/permissions";
import { playerBody } from "@/test/msw/squad";
import { playerActions } from "./playerActions";

const summary = (permissionLevel: PermissionLevel, active: boolean) =>
  playerActions(playerBody({ id: "p1", active }), permissionLevel).map((action) =>
    action.kind === "link"
      ? `${action.key} → ${action.to}`
      : `${action.key}${action.destructive ? " (destructive)" : ""}`,
  );

describe("playerActions", () => {
  it.each([
    [
      "ADMIN",
      true,
      ["open → /app/squad/p1", "edit → /app/squad/p1/edit", "release", "delete (destructive)"],
    ],
    ["EDIT_FULL", true, ["open → /app/squad/p1", "edit → /app/squad/p1/edit", "release"]],
    ["EDIT_PARTIAL", true, ["open → /app/squad/p1"]],
    ["VIEW_ONLY", true, ["open → /app/squad/p1"]],
    ["ADMIN", false, ["open → /app/squad/p1", "reactivate", "delete (destructive)"]],
    ["EDIT_FULL", false, ["open → /app/squad/p1", "reactivate"]],
    ["EDIT_PARTIAL", false, ["open → /app/squad/p1"]],
    ["VIEW_ONLY", false, ["open → /app/squad/p1"]],
  ] as [PermissionLevel, boolean, string[]][])(
    "%s, active %s: %j",
    (permissionLevel, active, expected) => {
      expect(summary(permissionLevel, active)).toEqual(expected);
    },
  );

  it("names each in-place action's dialog", () => {
    const dialogs = playerActions(playerBody(), "ADMIN").flatMap((action) =>
      action.kind === "dialog" ? [action.dialog] : [],
    );
    expect(dialogs).toEqual(["release", "delete"]);
  });
});
