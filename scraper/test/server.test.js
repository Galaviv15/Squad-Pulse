import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { after, before, beforeEach, describe, test } from "node:test";
import { createMemoryLogger } from "../src/log.js";
import { ScrapeQueue } from "../src/queue.js";
import { MAX_BODY_BYTES, createTriggerServer, parseTrigger } from "../src/server.js";
import { contractExample } from "./helpers.js";

const TOKEN = "s3cret-token-for-tests-0123456789abcdef";
const BODY = {
  runId: "r1",
  teamId: 7617,
  leagueId: 62,
  seasonId: 28,
  fixtures: true,
  standings: true,
};
// What parseTrigger adds to BODY: every optional property at its default.
const DEFAULTS = { playerStats: false, gameDetails: false, knownGameIds: [] };

let server;
let base;
let log;
let queue;
let started; // jobs the queue started, in order; each one runs until the queue shuts down

function newQueue() {
  started = [];
  return new ScrapeQueue({
    log,
    runJob: (job, signal) =>
      new Promise((resolve) => {
        started.push(job);
        signal.addEventListener("abort", resolve);
      }),
  });
}

before(async () => {
  log = createMemoryLogger();
  // The server keeps one queue object; each test gets a fresh queue behind it.
  server = createTriggerServer({
    token: TOKEN,
    queue: { submit: (job) => queue.submit(job) },
    log,
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
beforeEach(async () => {
  await queue?.shutdown();
  queue = newQueue();
});
after(async () => {
  await queue?.shutdown();
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
  assert.ok(!log.lines.some((line) => line.includes(TOKEN)), "the token must never be logged");
  assert.ok(!log.lines.some((line) => line.includes("s3cret")), "not even part of the token");
});

const post = (body, headers = {}, path = "/scrape") =>
  fetch(`${base}${path}`, {
    method: "POST",
    headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json", ...headers },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });

async function expectStatus(responsePromise, status) {
  const response = await responsePromise;
  assert.equal(response.status, status);
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

describe("POST /scrape", () => {
  test("202 QUEUED, and the scrape runs", async () => {
    assert.deepEqual(await expectStatus(post(BODY), 202), { status: "QUEUED", runId: "r1" });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(started, [{ ...BODY, ...DEFAULTS }]);
  });

  test("playerStats: absent → false, true → true, and only playerStats true is enough", async () => {
    await expectStatus(post({ ...BODY, runId: "p1", teamId: 1 }), 202);
    await expectStatus(post({ ...BODY, runId: "p2", teamId: 2, playerStats: true }), 202);
    await expectStatus(post({ ...BODY, runId: "p3", teamId: 3, playerStats: false }), 202);
    const statsOnly = { ...BODY, runId: "p4", teamId: 4, fixtures: false, standings: false };
    await expectStatus(post({ ...statsOnly, playerStats: true }), 202);
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(started, [{ ...BODY, ...DEFAULTS, runId: "p1", teamId: 1 }]);
    assert.deepEqual(parseTrigger({ ...BODY, playerStats: true }).playerStats, true);
    assert.deepEqual(parseTrigger({ ...BODY, playerStats: false }).playerStats, false);
    assert.deepEqual(parseTrigger(BODY), { ...BODY, ...DEFAULTS });
    assert.deepEqual(parseTrigger({ ...statsOnly, playerStats: true }), {
      ...statsOnly,
      ...DEFAULTS,
      playerStats: true,
    });
  });

  test("gameDetails: absent → false; true with and without knownGameIds", () => {
    assert.equal(parseTrigger(BODY).gameDetails, false);
    assert.deepEqual(parseTrigger({ ...BODY, gameDetails: true }), {
      ...BODY,
      ...DEFAULTS,
      gameDetails: true,
    });
    assert.deepEqual(parseTrigger({ ...BODY, gameDetails: true, knownGameIds: [5, 1, 3] }), {
      ...BODY,
      ...DEFAULTS,
      gameDetails: true,
      knownGameIds: [5, 1, 3],
    });
    assert.deepEqual(
      parseTrigger({ ...BODY, gameDetails: true, knownGameIds: [] }).knownGameIds,
      [],
    );
  });

  test("only gameDetails true (the other three false) is a valid trigger", async () => {
    const body = { ...BODY, fixtures: false, standings: false, playerStats: false };
    assert.deepEqual(await expectStatus(post({ ...body, gameDetails: true }), 202), {
      status: "QUEUED",
      runId: "r1",
    });
  });

  test("200 knownGameIds are accepted; the largest legal body fits the 4 KB limit", async () => {
    // Every property at its longest: a 100-character runId, ten-digit ids, all parts true.
    const ids = Array.from({ length: 200 }, (_, i) => 2147483647 - i);
    const largest = {
      runId: "r".repeat(100),
      teamId: 2147483647,
      leagueId: 2147483647,
      seasonId: 2147483647,
      fixtures: false,
      standings: false,
      playerStats: false,
      gameDetails: true,
      knownGameIds: ids,
    };
    const size = Buffer.byteLength(JSON.stringify(largest));
    assert.ok(size < MAX_BODY_BYTES, `${size} bytes`);
    assert.deepEqual(await expectStatus(post(largest), 202), {
      status: "QUEUED",
      runId: "r".repeat(100),
    });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(started[0].knownGameIds, ids);
  });

  test("the contract's trigger example parses", () => {
    const example = contractExample("contract-example-trigger");
    assert.deepEqual(parseTrigger(example), { ...example });
    assert.equal(example.gameDetails, true);
    assert.equal(example.knownGameIds.length, 2);
  });

  test("the accepted / deduplicated log lines count knownGameIds, never list them", async () => {
    const body = { ...BODY, runId: "k1", gameDetails: true, knownGameIds: [1122670, 1143937] };
    await expectStatus(post(body), 202);
    await new Promise((resolve) => setImmediate(resolve));
    await expectStatus(post({ ...body, runId: "k2" }), 202);
    const lines = log.lines.filter((l) => / run=k[12] trigger (accepted|deduplicated)/.test(l));
    assert.equal(lines.length, 2);
    for (const line of lines) {
      assert.match(line, / gameDetails=true knownGameIds=2( |$)/);
      assert.ok(!line.includes("1122670") && !line.includes("1143937"));
    }
  });

  test("400 nothing to scrape: all four parts false", async () => {
    const body = { ...BODY, fixtures: false, standings: false, playerStats: false };
    assert.deepEqual(await expectStatus(post({ ...body, gameDetails: false }), 400), {
      error: "nothing to scrape: fixtures, standings, playerStats and gameDetails are all false",
    });
  });

  test("runId null is accepted and echoed", async () => {
    assert.deepEqual(await expectStatus(post({ ...BODY, runId: null }), 202), {
      status: "QUEUED",
      runId: null,
    });
  });

  test("duplicate (same team, league, season): 202 ALREADY_QUEUED with the first runId", async () => {
    await expectStatus(post(BODY), 202);
    await new Promise((resolve) => setImmediate(resolve)); // now running
    const again = { ...BODY, runId: "r2", fixtures: false };
    assert.deepEqual(await expectStatus(post(again), 202), {
      status: "ALREADY_QUEUED",
      runId: "r1",
    });
    // And while waiting, not only while running.
    await expectStatus(post({ ...BODY, runId: "w1", teamId: 1 }), 202);
    assert.deepEqual(await expectStatus(post({ ...BODY, runId: "w2", teamId: 1 }), 202), {
      status: "ALREADY_QUEUED",
      runId: "w1",
    });
    assert.equal(started.length, 1);
  });

  test("queue full: 503 once 5 are waiting behind the running one", async () => {
    await expectStatus(post({ ...BODY, teamId: 100 }), 202);
    await new Promise((resolve) => setImmediate(resolve));
    for (let i = 1; i <= 5; i += 1) await expectStatus(post({ ...BODY, teamId: 100 + i }), 202);
    assert.deepEqual(await expectStatus(post({ ...BODY, teamId: 200 }), 503), {
      error: "queue full",
    });
  });

  test("401: no token, a wrong token, the right length one character off, not Bearer", async () => {
    const offByOne = TOKEN.slice(0, -1) + (TOKEN.endsWith("f") ? "e" : "f");
    for (const authorization of [
      undefined,
      "Bearer nope",
      `Bearer ${offByOne}`,
      `Basic ${TOKEN}`,
      `Bearer  ${TOKEN}`,
      "Bearer ",
    ]) {
      const headers = { "content-type": "application/json" };
      if (authorization !== undefined) headers.authorization = authorization;
      const response = await fetch(`${base}/scrape`, {
        method: "POST",
        headers,
        body: JSON.stringify(BODY),
      });
      assert.equal(response.status, 401, String(authorization));
      assert.deepEqual(await response.json(), { error: "unauthorized" });
    }
    assert.equal(started.length, 0);
  });

  const invalid = {
    "bad JSON": "{",
    "not an object": "[1]",
    "wrong type": { ...BODY, teamId: "7617" },
    "a fraction": { ...BODY, teamId: 1.5 },
    "missing property": Object.fromEntries(
      Object.entries(BODY).filter(([key]) => key !== "standings"),
    ),
    "out of range": { ...BODY, seasonId: 0 },
    "over Java's int": { ...BODY, leagueId: 2147483648 },
    "unknown property": { ...BODY, clubId: "x" },
    "both parts false": { ...BODY, fixtures: false, standings: false },
    "runId too long": { ...BODY, runId: "x".repeat(101) },
    'runId ""': { ...BODY, runId: "" },
    "runId with a control character": { ...BODY, runId: "a\nb" },
    "a boolean as a string": { ...BODY, fixtures: "true" },
    "playerStats null": { ...BODY, playerStats: null },
    'playerStats "true"': { ...BODY, playerStats: "true" },
    "playerStats 1": { ...BODY, playerStats: 1 },
    "an unknown property next to playerStats": { ...BODY, playerStats: true, lineups: true },
    'gameDetails "true"': { ...BODY, gameDetails: "true" },
    "gameDetails null": { ...BODY, gameDetails: null },
    "knownGameIds without gameDetails": { ...BODY, knownGameIds: [1] },
    "empty knownGameIds without gameDetails": { ...BODY, knownGameIds: [] },
    "knownGameIds with gameDetails false": { ...BODY, gameDetails: false, knownGameIds: [1] },
    "201 knownGameIds": {
      ...BODY,
      gameDetails: true,
      knownGameIds: Array.from({ length: 201 }, (_, i) => i + 1),
    },
    "a duplicate knownGameId": { ...BODY, gameDetails: true, knownGameIds: [7617, 7617] },
    "a knownGameId 0": { ...BODY, gameDetails: true, knownGameIds: [0] },
    "a knownGameId over Java's int": { ...BODY, gameDetails: true, knownGameIds: [2147483648] },
    "a fractional knownGameId": { ...BODY, gameDetails: true, knownGameIds: [1.5] },
    "a knownGameId as a string": { ...BODY, gameDetails: true, knownGameIds: ["7617"] },
    "a null knownGameId": { ...BODY, gameDetails: true, knownGameIds: [null] },
    "knownGameIds null": { ...BODY, gameDetails: true, knownGameIds: null },
    "knownGameIds an object": { ...BODY, gameDetails: true, knownGameIds: { 0: 1 } },
  };
  for (const [name, body] of Object.entries(invalid)) {
    test(`400: ${name}, with a fixed message that doesn't echo the input`, async () => {
      const answer = await expectStatus(post(body), 400);
      assert.deepEqual(Object.keys(answer), ["error"]);
      assert.ok(!answer.error.includes("clubId") && !answer.error.includes("7617"));
      assert.equal(started.length, 0);
    });
  }

  test("413 over 4 KB with Content-Length", async () => {
    await expectStatus(post({ ...BODY, runId: null, pad: "x".repeat(5000) }), 413);
  });

  test("413 over 4 KB, chunked (no Content-Length)", async () => {
    const status = await new Promise((resolve, reject) => {
      const req = httpRequest(`${base}/scrape`, {
        method: "POST",
        headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
      });
      req.on("response", (response) => {
        response.resume();
        resolve(response.statusCode);
      });
      req.on("error", reject);
      assert.equal(req.getHeader("content-length"), undefined);
      for (let i = 0; i < 5; i += 1) req.write("x".repeat(1000));
      req.end();
    });
    assert.equal(status, 413);
  });

  test("415 for another Content-Type; a charset parameter is fine", async () => {
    await expectStatus(post(BODY, { "content-type": "text/plain" }), 415);
    await expectStatus(post(BODY, { "content-type": "application/json; charset=utf-8" }), 202);
  });

  test("404 for another path, 405 for another method", async () => {
    await expectStatus(post(BODY, {}, "/other"), 404);
    const response = await fetch(`${base}/scrape`, {
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    assert.equal(response.status, 405);
    assert.equal(response.headers.get("allow"), "POST");
  });

  test("rejections are logged with the status only", async () => {
    await expectStatus(post({ ...BODY, clubId: "secret-club" }), 400);
    assert.ok(log.lines.some((l) => / INFO run=- trigger rejected status=400$/.test(l)));
    assert.ok(!log.lines.some((l) => l.includes("secret-club")));
  });
});

test("an unexpected error answers 500 and the service keeps running", async () => {
  const errorLog = createMemoryLogger();
  let calls = 0;
  const flaky = {
    submit: (job) => {
      calls += 1;
      if (calls === 1) throw new TypeError("boom: secret detail");
      return { status: "QUEUED", runId: job.runId };
    },
  };
  const other = createTriggerServer({ token: TOKEN, queue: flaky, log: errorLog });
  await new Promise((resolve) => other.listen(0, "127.0.0.1", resolve));
  const url = `http://127.0.0.1:${other.address().port}/scrape`;
  const send = () =>
    fetch(url, {
      method: "POST",
      headers: { authorization: `Bearer ${TOKEN}`, "content-type": "application/json" },
      body: JSON.stringify(BODY),
    });
  try {
    const first = await send();
    assert.equal(first.status, 500);
    assert.deepEqual(await first.json(), { error: "internal error" });
    assert.equal((await send()).status, 202);
    assert.ok(errorLog.lines.some((l) => / ERROR run=- trigger failed error=TypeError$/.test(l)));
    assert.ok(!errorLog.lines.some((l) => l.includes("secret detail")));
  } finally {
    other.closeAllConnections();
    await new Promise((resolve) => other.close(resolve));
  }
});
