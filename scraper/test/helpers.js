import { readFileSync } from "node:fs";
import { SourceNetworkError, SourceTimeoutError } from "../src/sourceErrors.js";

const here = (path) => new URL(path, import.meta.url);

export const fixture = (name) => readFileSync(here(`fixtures/${name}`), "utf8");

export const GAMES_HTML = fixture("team-games-7617-s28.html");
export const TABLE_XML = fixture("league-62-s28-table-round6.xml");
export const BLOCK_HTML = fixture("cloudflare-block-403.html");
export const STATS_XML = fixture("team-players-stats-7617-s28.xml");

/** A minimal league page: the standings placeholder only. `null` leaves an attribute out. */
export function leaguePage({ round = "6", box = "20", title = "טבלת ליגה" } = {}) {
  const attr = (name, value) => (value === null ? "" : ` ${name}='${value}'`);
  return `<!doctype html><html lang="he"><body><section class='placeholder league-standing-table'${attr(
    "data-table-index",
    box,
  )}${attr("data-table-title", title)}${attr("data-table-round", round)} data-table-type='table'><div class='bindData'></div></section></body></html>`;
}

/** The JSON block after a marker comment in docs/scraper-backend-contract.md. */
export function contractExample(marker = "contract-example-request") {
  const doc = readFileSync(here("../../docs/scraper-backend-contract.md"), "utf8");
  const after = doc.slice(doc.indexOf(`<!-- ${marker} -->`));
  const match = /```json\n([\s\S]*?)\n```/.exec(after);
  return JSON.parse(match[1]);
}

/** Time that only moves when something sleeps or a fake request takes time. */
export function fakeClock(start = Date.parse("2026-10-11T09:00:00Z")) {
  let time = start;
  const sleeps = [];
  return {
    sleeps,
    now: () => time,
    advance: (ms) => {
      time += ms;
    },
    sleep: async (ms, signal) => {
      signal?.throwIfAborted();
      sleeps.push(ms);
      time += ms;
    },
  };
}

/**
 * A source whose answers are scripted per path, in order. An answer is
 * {status, body, ms?} | {timeout: true} | {network: true}. Records every request.
 */
export function fakeSource(clock, script, origin = "https://www.football.org.il/") {
  const requests = [];
  const answer = (kind, url, timeoutMs) => {
    const path = new URL(url).pathname;
    requests.push({ kind, url, path, timeoutMs });
    const queue = script[path];
    if (!queue || queue.length === 0) throw new Error(`unexpected request ${path}`);
    const next = queue.length > 1 ? queue.shift() : queue[0];
    if (next.timeout) {
      clock.advance(timeoutMs);
      throw new SourceTimeoutError();
    }
    if (next.network) throw new SourceNetworkError();
    clock.advance(next.ms ?? 100);
    return { status: next.status, body: next.body };
  };
  return {
    origin,
    requests,
    closed: 0,
    async open() {
      return {
        navigate: async (url, timeoutMs) => answer("document", url, timeoutMs),
        fetchInPage: async (url, timeoutMs) => answer("fetch", url, timeoutMs),
        close: async () => {
          this.closed += 1;
        },
      };
    },
  };
}

export const OK_SCRIPT = () => ({
  "/team-details/team-games/": [{ status: 200, body: GAMES_HTML }],
  "/leagues/league/": [{ status: 200, body: leaguePage() }],
  "/Components.asmx/LeagueTable": [{ status: 200, body: TABLE_XML }],
});

export const JOB = Object.freeze({
  runId: "run-1",
  teamId: 7617,
  leagueId: 62,
  seasonId: 28,
  fixtures: true,
  standings: true,
});
