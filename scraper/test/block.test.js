import assert from "node:assert/strict";
import { test } from "node:test";
import { isBlocked } from "../src/block.js";
import { BLOCK_HTML, GAMES_HTML } from "./helpers.js";

test("the Cloudflare block page with 403 is a block", () =>
  assert.equal(isBlocked(403, BLOCK_HTML), true));
test("the Cloudflare block page with 200 is a block", () =>
  assert.equal(isBlocked(200, BLOCK_HTML), true));
test("any 403 is a block", () => assert.equal(isBlocked(403, ""), true));
test("a normal games page is not a block", () => assert.equal(isBlocked(200, GAMES_HTML), false));
test("a 500 is not a block", () => assert.equal(isBlocked(500, "oops"), false));
