import { format } from "node:util";

/**
 * Fails a test that logs a console.error or console.warn it didn't expect (KAN-58): React, React
 * Router, act() and MSW report their problems there, and a passing run must mean none happened.
 *
 * Installed once per test file by src/test/setup.ts, which marks each test's start
 * (beginConsoleGuardTest) and checks at its end (endConsoleGuardTest, in the setup file's
 * afterEach, which Vitest's default `sequence.hooks: "stack"` runs after the test file's own).
 *
 * Not built on vi.spyOn: console.error / console.warn are replaced by plain wrappers that record
 * the message and pass it on to the original, so the output still reaches the terminal, and
 * vi.restoreAllMocks() / vi.resetAllMocks() can't remove or empty them.
 *
 * Opting out: a test that expects a message mocks the method itself, e.g.
 * `vi.spyOn(console, "error").mockImplementation(() => {})`, and asserts the calls. Its mock
 * replaces the wrapper for that test; vi.restoreAllMocks() puts the wrapper back (a spy restores
 * what it replaced), and so does endConsoleGuardTest if a mock was left in place.
 *
 * A message logged outside a test (module load, beforeAll, or after a test's check, e.g. from a
 * stray promise) can't be blamed on one: it's kept and fails the file in afterAll
 * (endConsoleGuardFile).
 */

type Level = "error" | "warn";

const LEVELS: readonly Level[] = ["error", "warn"];

interface Logged {
  level: Level;
  message: string;
  origin: string;
}

/** The console methods the wrappers pass messages on to; exported for the guard's own tests. */
export const consoleGuardOriginals = { error: console.error, warn: console.warn };
const wrappers = {} as Record<Level, (...args: unknown[]) => void>;
let duringTest = false;
let inTest: Logged[] = [];
let outsideTests: Logged[] = [];

// Up to three stack frames in the app's or tests' sources (not dependencies, not this file):
// where the message came from. Empty when it came only from a dependency's own code.
function origin(): string {
  return (new Error().stack ?? "")
    .split("\n")
    .slice(1)
    .map((line) => line.trim().replace(/^at /, ""))
    .filter((frame) => /\/src\//.test(frame) && !/consoleGuard\.ts\b/.test(frame))
    .slice(0, 3)
    .join("\n    at ");
}

function wrap(level: Level) {
  return function guarded(...args: unknown[]) {
    const logged = { level, message: format(...args), origin: origin() };
    (duringTest ? inTest : outsideTests).push(logged);
    consoleGuardOriginals[level].apply(console, args);
  };
}

function install() {
  for (const level of LEVELS) {
    console[level] = wrappers[level];
  }
}

function describeAll(logged: Logged[]): string {
  return logged
    .map(
      ({ level, message, origin }) =>
        `console.${level}: ${message}${origin ? `\n    at ${origin}` : ""}`,
    )
    .join("\n\n");
}

// The guard's own stack says nothing about the message's origin, so the error carries none.
function failure(message: string): Error {
  const error = new Error(message);
  error.stack = `Error: ${message}`;
  return error;
}

/** Replaces console.error / console.warn with the guard's wrappers. Once per test file. */
export function installConsoleGuard() {
  for (const level of LEVELS) {
    wrappers[level] ??= wrap(level);
  }
  install();
}

/** A test starts: messages from now on are its own. */
export function beginConsoleGuardTest() {
  duringTest = true;
  inTest = [];
}

/**
 * A test ends: returns the error describing the messages it logged (null if none), and resets
 * the guard for the next test, with its wrappers back in place.
 */
export function endConsoleGuardTest(): Error | null {
  const logged = inTest;
  duringTest = false;
  inTest = [];
  install();
  if (logged.length === 0) return null;
  return failure(
    `Unexpected console output in this test (mock the method in the test if it's expected):\n\n${describeAll(logged)}`,
  );
}

/** The file ends: the error describing messages logged outside any test (null if none). */
export function endConsoleGuardFile(): Error | null {
  const logged = outsideTests;
  outsideTests = [];
  if (logged.length === 0) return null;
  return failure(`Unexpected console output outside a test:\n\n${describeAll(logged)}`);
}
