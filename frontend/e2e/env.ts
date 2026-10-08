import { randomBytes } from "node:crypto";
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { parseEnv } from "node:util";

/**
 * Where the E2E stack runs and what it's seeded with (KAN-53). Kept apart from local development
 * (backend on 8080, database `squadpulse`, Redis database 0) so a run never touches that data.
 */
export const BACKEND_PORT = 8081;
export const BACKEND_URL = `http://localhost:${BACKEND_PORT}`;
/**
 * The preview server for this suite (HTTPS). Not preview's default 4173, so a plain
 * `npm run preview` (or the dev server on 5173) can run alongside it.
 */
export const PREVIEW_PORT = 4174;
export const BASE_URL = `https://localhost:${PREVIEW_PORT}`;
export const E2E_DATABASE = "squadpulse_e2e";
export const E2E_REDIS_DATABASE = 1;

/**
 * The real access-token lifetime under test, so the silent-refresh scenario sees a real expiry.
 * 10s: long enough that a login and a few screens rarely cross it (crossing it is harmless — the
 * app refreshes), short enough that the expiry scenario waits only ~12s.
 */
export const ACCESS_TTL_SECONDS = 10;

/** The club and its one user (ADMIN), created by the backend's E2eSeeder. Test data only. */
export const E2E_CLUB_NAME = "מועדון E2E";
export const E2E_ADMIN = {
  email: "e2e-admin@squadpulse.test",
  password: "e2e-only-Password-1",
  fullName: "מנהל בדיקות",
};

/** Created through the API by the global setup; one name is distinctive enough to assert on. */
export const DISTINCTIVE_PLAYER = "אלמוג טסטוביץ׳";
export const E2E_PLAYERS = [
  { fullName: "יוסי כהן", primaryPosition: "GK", jerseyNumber: 1, dateOfBirth: "1995-03-14" },
  { fullName: "דני לוי", primaryPosition: "CB", jerseyNumber: 4, dateOfBirth: "1998-07-02" },
  {
    fullName: DISTINCTIVE_PLAYER,
    primaryPosition: "ST",
    jerseyNumber: 9,
    dateOfBirth: "2000-11-21",
  },
];

/** frontend/e2e/.logs: the backend's and the seeder's output (git-ignored, uploaded by CI). */
export const LOG_DIR = fileURLToPath(new URL("./.logs", import.meta.url));

/**
 * The environment the seeder and the backend under test get. The MongoDB / Redis credentials are
 * the docker-compose ones from the repo-root .env (or the process environment, which wins). The
 * MongoDB URI is built here, pointing at the E2E database instead of `squadpulse`.
 */
export function backendEnv(): Record<string, string> {
  const envFile = fileURLToPath(new URL("../../.env", import.meta.url));
  const fromFile = existsSync(envFile) ? parseEnv(readFileSync(envFile, "utf8")) : {};
  const value = (name: string): string => {
    const found = process.env[name] ?? fromFile[name];
    if (!found) {
      throw new Error(`E2E: ${name} is not set (repo-root .env, see .env.example)`);
    }
    return found;
  };
  const user = encodeURIComponent(value("MONGO_ROOT_USERNAME"));
  const password = encodeURIComponent(value("MONGO_ROOT_PASSWORD"));

  return {
    MONGODB_URI: `mongodb://${user}:${password}@localhost:27017/${E2E_DATABASE}?authSource=admin&directConnection=true`,
    REDIS_HOST: "localhost",
    REDIS_PORT: "6379",
    REDIS_PASSWORD: value("REDIS_PASSWORD"),
    JWT_SECRET: value("JWT_SECRET"),
    PASSWORD_PEPPER: value("PASSWORD_PEPPER"),
    SPRING_DATA_REDIS_DATABASE: String(E2E_REDIS_DATABASE),
    SERVER_PORT: String(BACKEND_PORT),
    SQUADPULSE_SECURITY_TOKEN_ACCESSTTL: `${ACCESS_TTL_SECONDS}s`,
    // Every test logs in as the one admin, from one IP; the wrong-password test, repeated
    // (--repeat-each) and run in parallel, would otherwise lock that admin out for 15 minutes.
    SQUADPULSE_SECURITY_LOGINTHROTTLE_MAXATTEMPTS: "1000",
    // Only the seeder reads these. The owner secret is fresh per run: it only has to match itself.
    E2E_OWNER_SECRET: randomBytes(32).toString("hex"),
    E2E_CLUB_NAME,
    E2E_ADMIN_EMAIL: E2E_ADMIN.email,
    E2E_ADMIN_PASSWORD: E2E_ADMIN.password,
    E2E_ADMIN_FULL_NAME: E2E_ADMIN.fullName,
    E2E_LOG_DIR: LOG_DIR,
  };
}
