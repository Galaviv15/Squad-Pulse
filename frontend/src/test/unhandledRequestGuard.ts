import type { SetupServer } from "msw/node";

/**
 * Fails a test that sends a request no MSW handler covers (KAN-60): a request the test didn't
 * mock is a request the app wasn't expected to make (a leaked token, a duplicate fetch, a request
 * after logout), and a passing run must mean none happened.
 *
 * Installed once per test file by src/test/setup.ts, on the console guard's model: each test's
 * start is marked (beginUnhandledRequestGuardTest) and checked at its end
 * (endUnhandledRequestGuardTest, in the setup file's afterEach); a request that lands outside a
 * test (e.g. from a stray promise after the check) can't be blamed on one and fails the file in
 * afterAll (endUnhandledRequestGuardFile).
 *
 * It listens to MSW's own "request:unhandled" event, not to the console, so a test that mocks
 * console.error can't hide a request. In MSW 2.15 that event fires before the onUnhandledRequest
 * strategy runs, a few microtasks after the fetch() call.
 *
 * The request itself is stopped by blockUnhandledRequest, the server's onUnhandledRequest: it
 * answers with a network error, so fetch rejects ("Failed to fetch") and nothing reaches the
 * network, without MSW's "error" strategy log, which the console guard would report a second
 * time.
 *
 * Opting out: a test that sends an unmocked request on purpose names it with
 * expectUnhandledRequest(method, path); the test then fails if that request doesn't happen.
 */

interface Unmocked {
  method: string;
  url: string;
}

interface Expected extends Unmocked {
  met: boolean;
}

let duringTest = false;
let currentTest: string | undefined;
let lastTest: string | undefined;
let inTest: Unmocked[] = [];
let expected: Expected[] = [];
let outsideTests: Unmocked[] = [];

function record(request: Request) {
  const unmocked = { method: request.method, url: request.url };
  if (!duringTest) {
    outsideTests.push(unmocked);
    return;
  }
  const expectation = expected.find(
    ({ method, url, met }) => !met && method === unmocked.method && url === unmocked.url,
  );
  if (expectation) {
    expectation.met = true;
  } else {
    inTest.push(unmocked);
  }
}

function describeAll(requests: Unmocked[]): string {
  return requests.map(({ method, url }) => `  ${method} ${url}`).join("\n");
}

// The guard's own stack says nothing about where the request came from, so the error carries none.
function failure(message: string): Error {
  const error = new Error(message);
  error.stack = `Error: ${message}`;
  return error;
}

const HINT = "Add a handler with server.use(...), or fix the code if the request shouldn't happen.";

/**
 * The server's onUnhandledRequest. Throwing a network-error Response makes MSW fail the request,
 * so fetch rejects. Returning, or calling print.error(), would let it through to the real network.
 */
export function blockUnhandledRequest(): never {
  throw Response.error();
}

/** Starts recording the server's unhandled requests. Once per test file, after server.listen. */
export function installUnhandledRequestGuard(server: Pick<SetupServer, "events">) {
  server.events.on("request:unhandled", ({ request }) => record(request));
}

/** A test starts: unhandled requests from now on are its own. */
export function beginUnhandledRequestGuardTest(name?: string) {
  duringTest = true;
  currentTest = name;
  inTest = [];
  expected = [];
}

/**
 * The one way to send an unmocked request on purpose: marks one `method` request to `path`
 * (resolved against the page's URL, query string included) as expected in the current test. It
 * must then happen exactly once before the test ends; a second one fails the test as usual.
 */
export function expectUnhandledRequest(method: string, path: string) {
  if (!duringTest) throw new Error("expectUnhandledRequest is only allowed inside a test");
  expected.push({ method, url: new URL(path, window.location.href).href, met: false });
}

/**
 * A test ends: returns the error describing its unmocked requests and unmet expectations (null if
 * none), and resets the guard for the next test.
 */
export function endUnhandledRequestGuardTest(): Error | null {
  const unmocked = inTest;
  const unmet = expected.filter(({ met }) => !met);
  duringTest = false;
  lastTest = currentTest;
  inTest = [];
  expected = [];
  const parts: string[] = [];
  if (unmocked.length > 0) {
    parts.push(`Unmocked request(s) in this test:\n${describeAll(unmocked)}\n${HINT}`);
  }
  if (unmet.length > 0) {
    parts.push(`Expected unmocked request(s) that never happened:\n${describeAll(unmet)}`);
  }
  return parts.length > 0 ? failure(parts.join("\n\n")) : null;
}

/** The file ends: the error describing unmocked requests sent outside any test (null if none). */
export function endUnhandledRequestGuardFile(): Error | null {
  const unmocked = outsideTests;
  outsideTests = [];
  if (unmocked.length === 0) return null;
  const after = lastTest ? ` (the last test that ran: "${lastTest}")` : "";
  return failure(`Unmocked request(s) outside a test${after}:\n${describeAll(unmocked)}\n${HINT}`);
}
