import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { test } from "node:test";
import { ConfigError, loadConfig } from "../src/config.js";

const ENTRY = fileURLToPath(new URL("../src/index.js", import.meta.url));
const GOOD = "a".repeat(32);

function start(env) {
  return spawnSync(process.execPath, [ENTRY], {
    env: { PATH: process.env.PATH, ...env },
    encoding: "utf8",
    timeout: 10000,
  });
}

const invalid = {
  "missing token": [{}, "SCRAPER_SERVICE_TOKEN", null],
  "short token": [
    { SCRAPER_SERVICE_TOKEN: "short-secret-value" },
    "SCRAPER_SERVICE_TOKEN",
    "short-secret-value",
  ],
  "token with whitespace": [
    { SCRAPER_SERVICE_TOKEN: "secret value with spaces 0123456789" },
    "SCRAPER_SERVICE_TOKEN",
    "secret value",
  ],
  "BACKEND_URL not a URL": [
    { SCRAPER_SERVICE_TOKEN: GOOD, BACKEND_URL: "not a url zzz" },
    "BACKEND_URL",
    "zzz",
  ],
  "BACKEND_URL ftp": [
    { SCRAPER_SERVICE_TOKEN: GOOD, BACKEND_URL: "ftp://host-zzz" },
    "BACKEND_URL",
    "host-zzz",
  ],
  "SCRAPER_PORT 0": [{ SCRAPER_SERVICE_TOKEN: GOOD, SCRAPER_PORT: "0" }, "SCRAPER_PORT", null],
  "SCRAPER_PORT 70000": [
    { SCRAPER_SERVICE_TOKEN: GOOD, SCRAPER_PORT: "70000" },
    "SCRAPER_PORT",
    "70000",
  ],
  "SCRAPER_PORT abc": [{ SCRAPER_SERVICE_TOKEN: GOOD, SCRAPER_PORT: "abc" }, "SCRAPER_PORT", "abc"],
  "SCRAPER_HOST with a space": [
    { SCRAPER_SERVICE_TOKEN: GOOD, SCRAPER_HOST: "bad host-zzz" },
    "SCRAPER_HOST",
    "host-zzz",
  ],
};

for (const [name, [env, variable, value]] of Object.entries(invalid)) {
  test(`${name}: exit 1, names ${variable}, never the value`, () => {
    const result = start(env);
    assert.equal(result.status, 1);
    const output = result.stdout + result.stderr;
    assert.match(output, new RegExp(`invalid configuration: ${variable}`));
    if (value !== null) assert.ok(!output.includes(value));
  });
}

test("defaults", () => {
  assert.deepEqual(loadConfig({ SCRAPER_SERVICE_TOKEN: GOOD }), {
    token: GOOD,
    backendUrl: "http://localhost:8080/",
    port: 8090,
    host: "127.0.0.1",
  });
});

test("Node below 24 is refused, naming the required and the actual major", () => {
  assert.throws(() => loadConfig({ SCRAPER_SERVICE_TOKEN: GOOD }, "22.23.2"), {
    name: "Error",
    message: "Node 24 or newer is required, this is Node 22",
  });
  assert.throws(() => loadConfig({ SCRAPER_SERVICE_TOKEN: GOOD }, "22.23.2"), ConfigError);
  assert.equal(loadConfig({ SCRAPER_SERVICE_TOKEN: GOOD }, "24.21.0").port, 8090);
});
