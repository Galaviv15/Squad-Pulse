import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  beginConsoleGuardTest,
  consoleGuardOriginals,
  endConsoleGuardFile,
  endConsoleGuardTest,
} from "./consoleGuard";

// The guard is installed by src/test/setup.ts. These tests call its check themselves, so a
// message they log on purpose is consumed here and doesn't fail them; each starts a "test" again
// afterwards, as setup.ts's beforeEach would.

/** Runs the guard's end-of-test check now and starts a fresh test. */
function checkNow() {
  const error = endConsoleGuardTest();
  beginConsoleGuardTest();
  return error;
}

let originalError: ReturnType<typeof vi.spyOn>;
let originalWarn: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  // The guard passes every message on to these; keep the deliberate ones out of the terminal.
  originalError = vi.spyOn(consoleGuardOriginals, "error").mockImplementation(() => {});
  originalWarn = vi.spyOn(consoleGuardOriginals, "warn").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("console guard", () => {
  it("reports an unexpected console.warn and console.error, with their text", () => {
    console.warn("careful: %s", "a warning");
    console.error("something broke", 42);

    const error = checkNow();

    expect(error?.message).toContain("Unexpected console output in this test");
    expect(error?.message).toContain("console.warn: careful: a warning");
    expect(error?.message).toContain("console.error: something broke 42");
    // Where it came from: this file.
    expect(error?.message).toContain("consoleGuard.test.ts");
  });

  it("passes every message on to the real console", () => {
    console.warn("shown", 1);
    console.error("shown too");

    expect(originalWarn).toHaveBeenCalledWith("shown", 1);
    expect(originalError).toHaveBeenCalledWith("shown too");
    expect(checkNow()).not.toBeNull();
  });

  it("reports nothing for a quiet test", () => {
    expect(checkNow()).toBeNull();
  });

  it("doesn't fail on console.log, info or debug", () => {
    vi.spyOn(console, "log").mockImplementation(() => {});
    vi.spyOn(console, "info").mockImplementation(() => {});
    vi.spyOn(console, "debug").mockImplementation(() => {});

    console.log("log");
    console.info("info");
    console.debug("debug");

    expect(checkNow()).toBeNull();
  });

  it("sees nothing while a test mocks the method itself (the opt-out)", () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => {});

    console.error("expected");

    expect(consoleError).toHaveBeenCalledWith("expected");
    expect(originalError).not.toHaveBeenCalled();
    expect(checkNow()).toBeNull();
  });

  // Runs after the previous test, whose afterEach called vi.restoreAllMocks().
  it("is still in place after a test that restored all mocks", () => {
    console.error("after restore");

    expect(checkNow()?.message).toContain("console.error: after restore");
  });

  it("puts itself back if a test left a mock in place", () => {
    console.warn = () => {};
    checkNow();

    console.warn("caught again");

    expect(checkNow()?.message).toContain("console.warn: caught again");
  });

  it("keeps a message logged outside a test for the end of the file", () => {
    endConsoleGuardTest();
    console.warn("between tests");
    beginConsoleGuardTest();

    expect(checkNow()).toBeNull();
    expect(endConsoleGuardFile()?.message).toContain(
      "Unexpected console output outside a test:\n\nconsole.warn: between tests",
    );
    expect(endConsoleGuardFile()).toBeNull();
  });

  // End to end: the check in setup.ts's afterEach fails the test.
  it.fails("fails a test that logs an unexpected warning", () => {
    console.warn("unexpected");
  });
});
