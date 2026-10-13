// The trigger endpoint: POST /scrape, called by the backend only (docs/scraper-backend-contract.md,
// "Trigger: backend → scraper"). Plain node:http. Error bodies are fixed texts: never the request.

import { createHash, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { hasControlCharacter } from "./text.js";

export const MAX_BODY_BYTES = 4096;
const JAVA_INT_MAX = 2147483647;
const REQUIRED = ["runId", "teamId", "leagueId", "seasonId", "fixtures", "standings"];
// Optional, absent = false: the backend leaves a false playerStats out of the body (KAN-72/73).
const OPTIONAL = ["playerStats"];

class BadRequest extends Error {}

const digest = (value) => createHash("sha256").update(value, "utf8").digest();

/** Constant-time: compares SHA-256 digests, so neither the content nor the length leaks. */
export function tokenMatches(authorization, expectedDigest) {
  const match = /^Bearer ([^\s]+)$/.exec(authorization ?? "");
  const provided = digest(match ? match[1] : "");
  return timingSafeEqual(provided, expectedDigest) && match !== null;
}

/**
 * Validates a trigger body. → a job, or throws BadRequest with a fixed message. The six REQUIRED
 * properties must be present; `playerStats` is optional (absent = `false`), but a boolean if
 * present. Any other property is rejected. At least one of the three parts must be `true`. The
 * job always has `playerStats` as a boolean.
 */
export function parseTrigger(body) {
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    throw new BadRequest("body must be a JSON object");
  }
  for (const key of Object.keys(body)) {
    if (!REQUIRED.includes(key) && !OPTIONAL.includes(key)) {
      throw new BadRequest("unknown property");
    }
  }
  for (const key of REQUIRED) {
    if (!(key in body)) throw new BadRequest(`missing property: ${key}`);
  }
  const { runId } = body;
  if (
    runId !== null &&
    (typeof runId !== "string" ||
      runId.length < 1 ||
      runId.length > 100 ||
      hasControlCharacter(runId))
  ) {
    throw new BadRequest("runId: must be null or 1..100 characters without control characters");
  }
  for (const key of ["teamId", "leagueId", "seasonId"]) {
    const value = body[key];
    if (!Number.isInteger(value) || value < 1 || value > JAVA_INT_MAX) {
      throw new BadRequest(`${key}: must be an integer in 1..${JAVA_INT_MAX}`);
    }
  }
  const job = { ...body, playerStats: "playerStats" in body ? body.playerStats : false };
  for (const key of ["fixtures", "standings", "playerStats"]) {
    if (typeof job[key] !== "boolean") throw new BadRequest(`${key}: must be a boolean`);
  }
  if (!job.fixtures && !job.standings && !job.playerStats) {
    throw new BadRequest("nothing to scrape: fixtures, standings and playerStats are all false");
  }
  return job;
}

export function createTriggerServer({ token, queue, log }) {
  const expectedDigest = digest(token);

  return createServer((request, response) => {
    const send = (status, body, headers = {}) => {
      if (status >= 400) log.info(null, "trigger rejected", { status });
      response.writeHead(status, { "content-type": "application/json", ...headers });
      response.end(JSON.stringify(body));
    };
    const reject = (status, error, headers) => {
      send(status, { error }, headers);
      // Don't read the rest of a body we refused.
      response.on("finish", () => request.destroy());
    };

    const path = (request.url ?? "").split("?")[0];
    if (path !== "/scrape") return reject(404, "not found");
    if (request.method !== "POST") return reject(405, "method not allowed", { allow: "POST" });
    if (!tokenMatches(request.headers.authorization, expectedDigest)) {
      return reject(401, "unauthorized", { "www-authenticate": "Bearer" });
    }
    const mediaType = (request.headers["content-type"] ?? "").split(";")[0].trim().toLowerCase();
    if (mediaType !== "application/json") {
      return reject(415, "Content-Type must be application/json");
    }
    const declared = request.headers["content-length"];
    if (declared !== undefined && Number(declared) > MAX_BODY_BYTES) {
      return reject(413, `body over ${MAX_BODY_BYTES} bytes`);
    }

    const chunks = [];
    let size = 0;
    let done = false;
    request.on("data", (chunk) => {
      if (done) return;
      size += chunk.length;
      if (size > MAX_BODY_BYTES) {
        done = true;
        reject(413, `body over ${MAX_BODY_BYTES} bytes`);
        return;
      }
      chunks.push(chunk);
    });
    request.on("error", () => {
      done = true;
    });
    request.on("end", () => {
      if (done) return;
      done = true;
      try {
        accept(Buffer.concat(chunks));
      } catch (error) {
        // An event handler must never throw: it would reach uncaughtException and stop the service.
        if (error instanceof BadRequest) return reject(400, error.message);
        log.error(null, "trigger failed", { error: error?.name ?? "unknown" });
        if (!response.headersSent) reject(500, "internal error");
      }
    });

    function accept(raw) {
      let body;
      try {
        body = JSON.parse(raw.toString("utf8"));
      } catch {
        throw new BadRequest("body is not valid JSON");
      }
      const job = parseTrigger(body);
      const result = queue.submit(job);
      const ids = { teamId: job.teamId, leagueId: job.leagueId, seasonId: job.seasonId };
      if (result.status === "QUEUED" || result.status === "ALREADY_QUEUED") {
        log.info(
          job.runId,
          result.status === "QUEUED" ? "trigger accepted" : "trigger deduplicated",
          {
            ...ids,
            fixtures: job.fixtures,
            standings: job.standings,
            playerStats: job.playerStats,
            ...(result.status === "ALREADY_QUEUED" ? { existingRun: result.runId ?? "-" } : {}),
          },
        );
        return send(202, { status: result.status, runId: result.runId ?? null });
      }
      if (result.status === "FULL") return reject(503, "queue full");
      return reject(503, "shutting down");
    }
  });
}
