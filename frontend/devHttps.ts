import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * The dev server's TLS key and certificate: per machine, made by mkcert (`npm run dev:cert`),
 * git-ignored. The dev server is HTTPS only so that every browser keeps the refresh cookie, which
 * is `Secure` (Safari drops a `Secure` cookie on plain http://localhost) — the cookie is never
 * weakened for development instead (KAN-56).
 */
export const DEV_CERT_DIR = ".cert";
export const DEV_KEY_FILE = "localhost-key.pem";
export const DEV_CERT_FILE = "localhost.pem";

/**
 * Reads the key and certificate from `dir`, or throws naming the command that creates them. There
 * is deliberately no http fallback.
 */
export function devHttpsOptions(dir: string): { key: Buffer; cert: Buffer } {
  const keyPath = join(dir, DEV_KEY_FILE);
  const certPath = join(dir, DEV_CERT_FILE);
  const missing = [keyPath, certPath].filter((path) => !existsSync(path));
  if (missing.length > 0) {
    throw new Error(
      `The dev server runs on HTTPS only and its local certificate is missing (${missing.join(", ")}). ` +
        `Create it with "npm run dev:cert" in frontend/ (needs mkcert; run "mkcert -install" once per machine first).`,
    );
  }
  return { key: readFileSync(keyPath), cert: readFileSync(certPath) };
}
