// SquadPulse scraper service (KAN-67): the backend triggers a scrape (POST /scrape), it runs in the
// background one at a time, and the result goes to the backend's POST /internal/scrape-results.

import { postScrapeResult } from "./backendClient.js";
import { createBrowserSource } from "./browserSource.js";
import { ConfigError, loadConfig } from "./config.js";
import { PARTS } from "./contract.js";
import { createLogger } from "./log.js";
import { ScrapeQueue } from "./queue.js";
import { runScrape } from "./scrape.js";
import { createTriggerServer } from "./server.js";

const log = createLogger();

let config;
try {
  config = loadConfig();
} catch (error) {
  if (!(error instanceof ConfigError)) throw error;
  log.error(null, `invalid configuration: ${error.message}`);
  process.exit(1);
}

const source = createBrowserSource();

async function runJob(job, signal) {
  log.info(job.runId, "scrape started", {
    teamId: job.teamId,
    leagueId: job.leagueId,
    seasonId: job.seasonId,
  });
  const payload = await runScrape(job, { source, log, signal });
  for (const part of PARTS) {
    if (payload[part] !== null) {
      log.info(job.runId, "part result", {
        part,
        status: payload[part].status,
        reason: payload[part].reason,
      });
    }
  }
  await postScrapeResult(payload, {
    backendUrl: config.backendUrl,
    token: config.token,
    log,
    signal,
  });
}

const queue = new ScrapeQueue({ runJob, log });
const server = createTriggerServer({ token: config.token, queue, log });

server.listen(config.port, config.host, () => {
  log.info(null, "listening", {
    host: config.host,
    port: config.port,
    backend: new URL(config.backendUrl).origin,
  });
});
server.on("error", (error) => {
  log.error(null, "server failed", { error: error?.code ?? error?.name });
  process.exit(1);
});

let stopping = false;
async function shutdown(signalName) {
  if (stopping) return;
  stopping = true;
  log.info(null, "shutting down", { signal: signalName });
  server.close();
  server.closeAllConnections();
  const { dropped, aborted } = await queue.shutdown();
  if (aborted !== undefined) log.warn(aborted, "running scrape aborted");
  for (const runId of dropped) log.warn(runId, "queued scrape dropped");
  process.exit(0);
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));
