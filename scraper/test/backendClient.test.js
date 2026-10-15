import assert from "node:assert/strict";
import { createServer } from "node:http";
import { after, before, beforeEach, test } from "node:test";
import { postScrapeResult } from "../src/backendClient.js";
import { createMemoryLogger } from "../src/log.js";
import { runScrape } from "../src/scrape.js";
import { parsePlayerStats } from "../src/parsePlayerStats.js";
import {
  JOB_ALL,
  OK_SCRIPT,
  STATS_XML,
  contractExample,
  fakeClock,
  fakeSource,
} from "./helpers.js";

const TOKEN = "backend-token-for-tests-0123456789abcdef";
let server;
let backendUrl;
let received;
let answers; // per request, in order: { status, body } or "drop" (destroy the socket)

before(async () => {
  server = createServer((request, response) => {
    const chunks = [];
    request.on("data", (c) => chunks.push(c));
    request.on("end", () => {
      received.push({
        method: request.method,
        url: request.url,
        authorization: request.headers.authorization,
        contentType: request.headers["content-type"],
        body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
      });
      const answer = answers.length > 1 ? answers.shift() : answers[0];
      if (answer === "drop") return request.socket.destroy();
      response.writeHead(answer.status, { "content-type": "application/json" });
      response.end(JSON.stringify(answer.body ?? {}));
    });
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  backendUrl = `http://127.0.0.1:${server.address().port}`;
});
after(() => new Promise((resolve) => server.close(resolve)));
beforeEach(() => {
  received = [];
});

const STORED = {
  fixtures: { outcome: "STORED", reason: null },
  standings: { outcome: "REJECTED", reason: "NOT_MAPPED" },
  playerStats: { outcome: "STORED", reason: null },
  gameDetails: { outcome: "STORED", reason: null },
  clubsUpdated: 1,
  warnings: [
    { code: "TEAM_NOT_IN_STANDINGS", count: null },
    { code: "UNRESOLVED_TEAM_NAMES", count: 2 },
  ],
};

async function send(answerList, payload = contractExample()) {
  answers = answerList;
  const log = createMemoryLogger();
  const clock = fakeClock();
  const result = await postScrapeResult(payload, { backendUrl, token: TOKEN, log, clock });
  return { result, log, clock };
}

test("POST /internal/scrape-results with the bearer token and JSON", async () => {
  const payload = contractExample();
  await send([{ status: 200, body: STORED }], payload);
  assert.equal(received.length, 1);
  assert.deepEqual(received[0], {
    method: "POST",
    url: "/internal/scrape-results",
    authorization: `Bearer ${TOKEN}`,
    contentType: "application/json",
    body: payload,
  });
});

test("200: outcomes logged; REJECTED and TEAM_NOT_IN_STANDINGS at WARN, other warnings at INFO", async () => {
  const { result, log } = await send([{ status: 200, body: STORED }]);
  assert.deepEqual(result, { delivered: true });
  const has = (pattern) =>
    assert.ok(
      log.lines.some((l) => pattern.test(l)),
      String(pattern),
    );
  has(/ INFO run=\S+ backend outcome part=fixtures outcome=STORED$/);
  has(/ INFO run=\S+ backend outcome part=playerStats outcome=STORED$/);
  has(/ INFO run=\S+ backend outcome part=gameDetails outcome=STORED$/);
  has(/ WARN run=\S+ backend outcome part=standings outcome=REJECTED reason=NOT_MAPPED$/);
  has(/ WARN run=\S+ backend warning code=TEAM_NOT_IN_STANDINGS$/);
  has(/ INFO run=\S+ backend warning code=UNRESOLVED_TEAM_NAMES count=2$/);
  has(/ INFO run=\S+ backend clubs updated clubsUpdated=1$/);
});

for (const [name, first] of [
  ["409", { status: 409, body: { code: "CONCURRENT_MODIFICATION" } }],
  ["500", { status: 500 }],
  ["a network error", "drop"],
]) {
  test(`${name}: exactly one retry after 5 s`, async () => {
    const { result, clock } = await send([first, { status: 200, body: STORED }]);
    assert.equal(received.length, 2);
    assert.deepEqual(clock.sleeps, [5000]);
    assert.deepEqual(result, { delivered: true });
  });

  test(`${name} twice: dropped, logged at ERROR`, async () => {
    const { result, log } = await send([first]);
    assert.equal(received.length, 2);
    assert.deepEqual(result, { delivered: false });
    assert.ok(log.lines.some((l) => / ERROR .*result dropped/.test(l)));
  });
}

for (const status of [400, 401, 413, 415]) {
  test(`${status}: no retry, logged at ERROR`, async () => {
    const body = { status, details: ["fixtures.games[0].date: invalid value"] };
    const { result, log, clock } = await send([{ status, body }]);
    assert.equal(received.length, 1);
    assert.deepEqual(clock.sleeps, []);
    assert.deepEqual(result, { delivered: false });
    const line = log.lines.find((l) => / ERROR .*backend refused the result/.test(l));
    assert.ok(line.includes(`status=${status}`));
    assert.equal(line.includes("fixtures.games[0].date"), status === 400);
  });
}

test("a timeout counts as a network error (retried once)", async () => {
  answers = [{ status: 200, body: STORED }];
  const log = createMemoryLogger();
  const clock = fakeClock();
  let calls = 0;
  const fetchImpl = async (url, init) => {
    calls += 1;
    if (calls === 1) {
      await new Promise((resolve, reject) =>
        init.signal.addEventListener("abort", () => reject(init.signal.reason)),
      );
    }
    return fetch(url, init);
  };
  const result = await postScrapeResult(contractExample(), {
    backendUrl,
    token: TOKEN,
    log,
    clock,
    fetchImpl,
    timeoutMs: 20,
  });
  assert.equal(calls, 2);
  assert.deepEqual(result, { delivered: true });
  assert.ok(log.lines.some((l) => / WARN .*retrying error=timeout$/.test(l)));
});

test("never logs the token, team names or the payload", async () => {
  const { log } = await send([{ status: 400, body: { details: [] } }]);
  const all = log.lines.join("\n");
  assert.ok(!all.includes(TOKEN));
  assert.ok(!all.includes("שדרות"));
});

test("a REJECTED playerStats outcome is logged at WARN with its reason", async () => {
  const answer = { ...STORED, playerStats: { outcome: "REJECTED", reason: "NOT_MAPPED" } };
  const { log } = await send([{ status: 200, body: answer }]);
  assert.ok(
    log.lines.some((l) =>
      / WARN run=\S+ backend outcome part=playerStats outcome=REJECTED reason=NOT_MAPPED$/.test(l),
    ),
  );
});

test("a full scrape and post from the fixtures logs no player name or player_id", async () => {
  const log = createMemoryLogger();
  const clock = fakeClock();
  const payload = await runScrape(JOB_ALL, {
    source: fakeSource(clock, OK_SCRIPT()),
    clock,
    log,
    random: () => 0.5,
  });
  assert.equal(payload.playerStats.status, "OK");
  answers = [{ status: 200, body: STORED }];
  await postScrapeResult(payload, { backendUrl, token: TOKEN, log, clock });
  assert.equal(received.length, 1);
  assert.equal(received[0].body.playerStats.players.length, 34);

  const players = parsePlayerStats(STATS_XML);
  const all = log.lines.join("\n");
  assert.ok(log.lines.some((l) => / backend outcome part=playerStats outcome=STORED$/.test(l)));
  for (const { name, playerId } of players) {
    assert.ok(!all.includes(name), "a player name was logged");
    // Ids are 5–6 digits; as a whole token, so a duration in ms can't match by chance.
    assert.ok(!new RegExp(`\\b${playerId}\\b`).test(all), "a player_id was logged");
  }
});
