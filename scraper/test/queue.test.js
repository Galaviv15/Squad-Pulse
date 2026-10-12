import assert from "node:assert/strict";
import { test } from "node:test";
import { createMemoryLogger } from "../src/log.js";
import { ScrapeQueue } from "../src/queue.js";

const job = (teamId, runId = `r${teamId}`) => ({
  runId,
  teamId,
  leagueId: 1,
  seasonId: 1,
  fixtures: true,
  standings: false,
});

test("one at a time, FIFO", async () => {
  const order = [];
  let running = 0;
  const queue = new ScrapeQueue({
    log: createMemoryLogger(),
    runJob: async (j) => {
      running += 1;
      assert.equal(running, 1);
      order.push(j.teamId);
      await new Promise((resolve) => setTimeout(resolve, 5));
      running -= 1;
    },
  });
  for (const id of [1, 2, 3]) assert.equal(queue.submit(job(id)).status, "QUEUED");
  await queue.idle();
  assert.deepEqual(order, [1, 2, 3]);
  // Once done, the same key can be queued again.
  assert.equal(queue.submit(job(1)).status, "QUEUED");
  await queue.idle();
});

test("a crashing job is logged and the next one still runs", async () => {
  const log = createMemoryLogger();
  const ran = [];
  const queue = new ScrapeQueue({
    log,
    runJob: async (j) => {
      ran.push(j.teamId);
      if (j.teamId === 1) throw new TypeError("boom");
    },
  });
  queue.submit(job(1));
  queue.submit(job(2));
  await queue.idle();
  assert.deepEqual(ran, [1, 2]);
  assert.ok(log.lines.some((l) => / ERROR run=r1 scrape crashed error=TypeError$/.test(l)));
});

test("shutdown aborts the running job, drops the waiting ones and refuses new ones", async () => {
  let signalSeen;
  const queue = new ScrapeQueue({
    log: createMemoryLogger(),
    runJob: (j, signal) =>
      new Promise((_, reject) => {
        signalSeen = signal;
        signal.addEventListener("abort", () => reject(signal.reason));
      }),
  });
  queue.submit(job(1));
  queue.submit(job(2));
  queue.submit(job(3, null));
  await new Promise((resolve) => setImmediate(resolve));
  const result = await queue.shutdown();
  assert.equal(signalSeen.aborted, true);
  assert.deepEqual(result, { dropped: ["r2", null], aborted: "r1" });
  assert.equal(queue.submit(job(4)).status, "CLOSED");
});
