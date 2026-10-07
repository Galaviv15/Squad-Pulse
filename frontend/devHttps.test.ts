import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { DEV_CERT_FILE, DEV_KEY_FILE, devHttpsOptions } from "./devHttps.ts";

describe("devHttpsOptions", () => {
  let dir: string;

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), "squadpulse-cert-"));
  });

  afterEach(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  it("throws naming the command that creates the certificate when it is missing", () => {
    expect(() => devHttpsOptions(join(dir, "absent"))).toThrow(/npm run dev:cert/);
    expect(() => devHttpsOptions(join(dir, "absent"))).toThrow(/mkcert -install/);
  });

  it("throws when only one of the two files exists", () => {
    writeFileSync(join(dir, DEV_KEY_FILE), "key");
    expect(() => devHttpsOptions(dir)).toThrow(DEV_CERT_FILE);
  });

  it("returns the key and certificate when both exist", () => {
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, DEV_KEY_FILE), "key");
    writeFileSync(join(dir, DEV_CERT_FILE), "cert");
    const options = devHttpsOptions(dir);
    expect(options.key.toString()).toBe("key");
    expect(options.cert.toString()).toBe("cert");
  });
});
