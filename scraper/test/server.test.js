import assert from "node:assert/strict";
import { request as httpRequest } from "node:http";
import { after, before, beforeEach, describe, test } from "node:test";
import { createMemoryLogger } from "../src/log.js";
import { ScrapeQueue } from "../src/queue.js";
import { createTriggerServer } from "../src/server.js";

const TOKEN = "s3cret-token-for-tests-0123456789abcdef";
const BODY = {
  runId: "r1",
  teamId: 7617,
  leagueId: 62,
  seasonId: 28,
  fixtures: true,
  standings: true,
};

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
    assert.deepEqual(started, [BODY]);
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
