// One real headless-Chromium scrape against a local server that serves the fixtures at the site's
// paths. Never the live site.

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { after, before, beforeEach, test } from "node:test";
import { createBrowserSource } from "../src/browserSource.js";
import { createMemoryLogger } from "../src/log.js";
import { parseGamesPage } from "../src/parseFixtures.js";
import { parseLeagueTable } from "../src/parseStandings.js";
import { runScrape } from "../src/scrape.js";
import { parsePlayerStats } from "../src/parsePlayerStats.js";
import {
  BLOCK_HTML,
  GAMES_HTML,
  JOB,
  JOB_ALL,
  STATS_XML,
  TABLE_XML,
  leaguePage,
} from "./helpers.js";

// Resources a real page references: same-origin and third-party scripts, a stylesheet, an image,
// and an inline script that fires the site's own XHR. The route rule must abort every one.
const EXTRAS = `
<link rel="stylesheet" href="/static/site.css">
<link rel="icon" href="/favicon.ico">
<script src="/static/app.js"></script>
<script src="https://www.googletagmanager.invalid/gtm.js"></script>
<img src="/files/logo.png" alt=""><img src="https://cdn.third-party.invalid/pixel.gif" alt="">
<script>fetch("/Components.asmx/LeagueGamesList?league_id=62"); new Image().src = "/files/beacon.gif";</script>`;

let server;
let origin;
let received;
let blockGames;
let hang; // a path the server never answers

before(async () => {
  server = createServer((request, response) => {
    received.push(`${request.method} ${request.url}`);
    const url = new URL(request.url, "http://localhost");
    if (url.pathname === hang) return; // never answered; closed with the server
    const send = (status, type, body) => {
      response.writeHead(status, { "content-type": `${type}; charset=utf-8` });
      response.end(body);
    };
    if (url.pathname === "/team-details/team-games/") {
      if (blockGames) return send(403, "text/html", BLOCK_HTML);
      return send(200, "text/html", GAMES_HTML.replace("</body>", `${EXTRAS}</body>`));
    }
    if (url.pathname === "/leagues/league/") {
      return send(200, "text/html", leaguePage().replace("</body>", `${EXTRAS}</body>`));
    }
    if (url.pathname === "/Components.asmx/LeagueTable") return send(200, "text/xml", TABLE_XML);
    if (url.pathname === "/Components.asmx/GetTeamPlayersStatisticsList") {
      return send(200, "text/xml", STATS_XML);
    }
    return send(404, "text/plain", "not here");
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  origin = `http://127.0.0.1:${server.address().port}/`;
});
after(() => {
  server.closeAllConnections();
  return new Promise((resolve) => server.close(resolve));
});
beforeEach(() => {
  received = [];
  blockGames = false;
  hang = null;
});

const scrape = (log, options = {}, job = JOB) =>
  runScrape(job, {
    source: createBrowserSource({ origin }),
    log,
    pauseMinMs: 10,
    pauseMaxMs: 20,
    retryDelayMs: 10,
    ...options,
  });

test("a full scrape: both parts OK, equal to the parsers' results, exactly 3 requests reach the server", async () => {
  const log = createMemoryLogger();
  const payload = await scrape(log);
  assert.equal(payload.fixtures.status, "OK", JSON.stringify(payload.fixtures));
  assert.equal(payload.standings.status, "OK", JSON.stringify(payload.standings));
  assert.deepEqual(payload.fixtures.games, parseGamesPage(GAMES_HTML));
  assert.deepEqual(payload.standings.rows, parseLeagueTable(TABLE_XML));
  assert.equal(payload.standings.round, 6);
  assert.deepEqual(received, [
    "GET /team-details/team-games/?team_id=7617&season_id=28",
    "GET /leagues/league/?league_id=62&season_id=28",
    "GET /Components.asmx/LeagueTable?league_id=62&season_id=28&box=20&round_id=6&componentTitle=%D7%98%D7%91%D7%9C%D7%AA%20%D7%9C%D7%99%D7%92%D7%94",
  ]);
});

test("a block page with 403: SOURCE_BLOCKED after 1 request", async () => {
  blockGames = true;
  const log = createMemoryLogger();
  const payload = await scrape(log);
  assert.equal(payload.fixtures.reason, "SOURCE_BLOCKED");
  assert.equal(payload.standings.reason, "SOURCE_BLOCKED");
  assert.deepEqual(received, ["GET /team-details/team-games/?team_id=7617&season_id=28"]);
  assert.ok(log.lines.some((l) => / WARN .*source blocked .*status=403$/.test(l)));
});

test("a navigation timeout is TIMEOUT, after one retry", async () => {
  hang = "/team-details/team-games/";
  const payload = await scrape(createMemoryLogger(), { navigationTimeoutMs: 300 });
  assert.equal(payload.fixtures.reason, "TIMEOUT");
  assert.equal(payload.standings.status, "OK");
  assert.equal(received.filter((r) => r.includes("/team-games/")).length, 2);
});

test("an in-page fetch timeout (AbortController in the page) is TIMEOUT", async () => {
  hang = "/Components.asmx/LeagueTable";
  const payload = await scrape(createMemoryLogger(), { fetchTimeoutMs: 300 });
  assert.equal(payload.fixtures.status, "OK");
  assert.equal(payload.standings.reason, "TIMEOUT");
  assert.equal(received.filter((r) => r.includes("LeagueTable")).length, 2);
});

test("all three parts: exactly 4 requests reach the server, the stats URL as the browser sends it", async () => {
  const log = createMemoryLogger();
  const payload = await scrape(log, {}, JOB_ALL);
  assert.equal(payload.fixtures.status, "OK", JSON.stringify(payload.fixtures));
  assert.equal(payload.standings.status, "OK", JSON.stringify(payload.standings));
  assert.equal(payload.playerStats.status, "OK", JSON.stringify(payload.playerStats));
  assert.equal(payload.playerStats.players.length, 34);
  assert.deepEqual(payload.playerStats.players, parsePlayerStats(STATS_XML));
  // Only these four: the pages' extra resources and the site's own XHR (EXTRAS) were aborted.
  assert.deepEqual(received, [
    "GET /team-details/team-games/?team_id=7617&season_id=28",
    "GET /Components.asmx/GetTeamPlayersStatisticsList?teamId=7617&seasonId=28&isFemale=%22False%22",
    "GET /leagues/league/?league_id=62&season_id=28",
    "GET /Components.asmx/LeagueTable?league_id=62&season_id=28&box=20&round_id=6&componentTitle=%D7%98%D7%91%D7%9C%D7%AA%20%D7%9C%D7%99%D7%92%D7%94",
  ]);
});
