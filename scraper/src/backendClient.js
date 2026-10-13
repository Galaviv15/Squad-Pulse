// Posts a scrape result to the backend's POST /internal/scrape-results (docs/scraper-backend-contract.md).

import { realClock } from "./clock.js";
import { PARTS } from "./contract.js";

const RETRY_STATUSES = (status) => status === 409 || status >= 500;
const WARN_WARNINGS = new Set(["TEAM_NOT_IN_STANDINGS"]);

/**
 * One retry after 5 s on a 409, a 5xx, a network error or a timeout; none on any other status.
 * Logs outcomes only, never the payload, its team or player names, a player id, a `detail` or the
 * token.
 */
export async function postScrapeResult(payload, deps) {
  const {
    backendUrl,
    token,
    log,
    fetchImpl = fetch,
    clock = realClock,
    timeoutMs = 15_000,
    retryDelayMs = 5_000,
    signal,
  } = deps;
  const { runId } = payload;
  const base = backendUrl.endsWith("/") ? backendUrl : `${backendUrl}/`;
  const url = new URL("internal/scrape-results", base);
  const body = JSON.stringify(payload);

  for (let attempt = 0; attempt < 2; attempt += 1) {
    if (attempt > 0) await clock.sleep(retryDelayMs, signal);
    let response;
    try {
      const timeout = AbortSignal.timeout(timeoutMs);
      response = await fetchImpl(url, {
        method: "POST",
        headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
        body,
        signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
      });
    } catch (error) {
      signal?.throwIfAborted();
      const what = error?.name === "TimeoutError" ? "timeout" : "network error";
      if (attempt === 0) {
        log.warn(runId, "backend not reached, retrying", { error: what });
        continue;
      }
      log.error(runId, "backend not reached, result dropped", { error: what });
      return { delivered: false };
    }

    if (response.status === 200) {
      logOutcomes(runId, await readJson(response), log);
      return { delivered: true };
    }
    if (RETRY_STATUSES(response.status)) {
      await response.body?.cancel();
      if (attempt === 0) {
        log.warn(runId, "backend answered, retrying", { status: response.status });
        continue;
      }
      log.error(runId, "backend answered, result dropped", { status: response.status });
      return { delivered: false };
    }
    // 400 / 401 / 413 / 415: never retried. A 400 means the scraper built a bad payload (a bug).
    const fields = { status: response.status };
    if (response.status === 400) {
      const details = (await readJson(response))?.details;
      if (Array.isArray(details)) fields.details = JSON.stringify(details.slice(0, 20));
    } else {
      await response.body?.cancel();
    }
    log.error(runId, "backend refused the result", fields);
    return { delivered: false };
  }
  return { delivered: false };
}

async function readJson(response) {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

function logOutcomes(runId, answer, log) {
  if (answer === null || typeof answer !== "object") {
    log.warn(runId, "backend stored the result, unreadable answer");
    return;
  }
  for (const part of PARTS) {
    const outcome = answer[part]?.outcome;
    if (outcome === "REJECTED") {
      log.warn(runId, "backend outcome", { part, outcome, reason: answer[part]?.reason });
    } else {
      log.info(runId, "backend outcome", { part, outcome });
    }
  }
  log.info(runId, "backend clubs updated", { clubsUpdated: answer.clubsUpdated });
  for (const warning of Array.isArray(answer.warnings) ? answer.warnings : []) {
    const fields = { code: warning?.code, count: warning?.count ?? undefined };
    if (WARN_WARNINGS.has(warning?.code)) log.warn(runId, "backend warning", fields);
    else log.info(runId, "backend warning", fields);
  }
}
