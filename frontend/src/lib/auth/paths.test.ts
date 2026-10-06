import { describe, expect, it } from "vitest";
import { safeNextPath, withNext } from "./paths";

describe("safeNextPath", () => {
  it.each([
    ["/app", "/app"],
    ["/app/x?y#z", "/app/x?y#z"],
    ["/app/squad?position=GK", "/app/squad?position=GK"],
    ["/app/", "/app/"],
    // Dot segments are resolved first; what's left is still under /app.
    ["/app/a/../b", "/app/b"],
  ])("accepts %s", (raw, expected) => {
    expect(safeNextPath(raw)).toBe(expected);
  });

  it.each([
    ["an absolute URL", "https://evil.com"],
    ["an absolute URL to an /app path", "https://evil.com/app"],
    ["a protocol-relative URL", "//evil.com"],
    ["a protocol-relative URL to an /app path", "//evil.com/app"],
    ["a backslash", "/\\evil.com"],
    ["a backslash to an /app path", "/\\evil.com/app"],
    ["javascript:", "javascript:alert(1)"],
    ["an encoded protocol-relative URL", "%2F%2Fevil.com"],
    ["an encoded path, still under /", "/%2F%2Fevil.com"],
    ["a backend path", "/auth/login"],
    ["a path that only starts like /app", "/application"],
    ["a dot segment out of /app", "/app/../auth/login"],
    ["an encoded dot segment out of /app", "/app/%2e%2e/auth/login"],
    ["a double slash inside /app", "/app//evil.com"],
    ["the login route", "/app/login"],
    ["the login route with a query", "/app/login?next=/app/x"],
    ["the login route with a trailing slash", "/app/login/"],
    ["the forgot-password route", "/app/forgot-password"],
    ["the reset-password route", "/app/reset-password"],
    ["a relative path", "app/x"],
    ["leading whitespace", " //evil.com"],
    ["an empty string", ""],
  ])("rejects %s", (_name, raw) => {
    expect(safeNextPath(raw)).toBe("/app");
  });

  it.each([null, undefined])("falls back to /app when it's missing (%s)", (raw) => {
    expect(safeNextPath(raw)).toBe("/app");
  });
});

describe("withNext", () => {
  it("adds next as an encoded query parameter", () => {
    expect(withNext("/app/login", "/app/squad?position=GK")).toBe(
      "/app/login?next=%2Fapp%2Fsquad%3Fposition%3DGK",
    );
  });

  it.each([null, undefined, "", "/app"])("leaves it out when it's %s", (next) => {
    expect(withNext("/app/login", next)).toBe("/app/login");
  });
});
