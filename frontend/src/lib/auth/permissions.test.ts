import { describe, expect, it } from "vitest";
import { hasPermission, PERMISSION_LEVELS, type PermissionLevel } from "./permissions";

describe("hasPermission", () => {
  it("orders the levels as the backend's hierarchy, highest first", () => {
    expect(PERMISSION_LEVELS).toEqual(["ADMIN", "EDIT_FULL", "EDIT_PARTIAL", "VIEW_ONLY"]);
  });

  // [level, required, allowed]: every pair.
  const table: [PermissionLevel, PermissionLevel, boolean][] = [
    ["ADMIN", "ADMIN", true],
    ["ADMIN", "EDIT_FULL", true],
    ["ADMIN", "EDIT_PARTIAL", true],
    ["ADMIN", "VIEW_ONLY", true],
    ["EDIT_FULL", "ADMIN", false],
    ["EDIT_FULL", "EDIT_FULL", true],
    ["EDIT_FULL", "EDIT_PARTIAL", true],
    ["EDIT_FULL", "VIEW_ONLY", true],
    ["EDIT_PARTIAL", "ADMIN", false],
    ["EDIT_PARTIAL", "EDIT_FULL", false],
    ["EDIT_PARTIAL", "EDIT_PARTIAL", true],
    ["EDIT_PARTIAL", "VIEW_ONLY", true],
    ["VIEW_ONLY", "ADMIN", false],
    ["VIEW_ONLY", "EDIT_FULL", false],
    ["VIEW_ONLY", "EDIT_PARTIAL", false],
    ["VIEW_ONLY", "VIEW_ONLY", true],
  ];

  it.each(table)("%s for %s: %s", (level, required, allowed) => {
    expect(hasPermission(level, required)).toBe(allowed);
  });

  it("covers every pair", () => {
    expect(table).toHaveLength(PERMISSION_LEVELS.length ** 2);
  });
});
