import { defineConfig, devices } from "@playwright/test";
import { BACKEND_URL, BASE_URL, PREVIEW_PORT, backendEnv } from "./e2e/env.ts";

/**
 * End-to-end tests (KAN-53): the built SPA, served by `vite preview` over HTTPS, against the real
 * backend + MongoDB + Redis, in Chromium and WebKit. `npm run e2e`; prerequisites in the README
 * ("End-to-end tests"). Detailed cases stay in Vitest + MSW.
 */
export default defineConfig({
  testDir: "./e2e",
  globalSetup: "./e2e/global-setup.ts",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  // No retries, so a flaky test shows up instead of being hidden.
  retries: 0,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],
  use: {
    baseURL: BASE_URL,
    // The certificate is the local mkcert one, or a throwaway self-signed one in CI.
    ignoreHTTPSErrors: true,
    trace: "retain-on-failure",
    video: "retain-on-failure",
  },
  projects: [
    { name: "chromium", use: { ...devices["Desktop Chrome"] } },
    { name: "webkit", use: { ...devices["Desktop Safari"] } },
  ],
  // Started in this order, and killed (the whole process group) after the run. Never reused: a
  // run always starts from a freshly seeded backend, and a busy port fails the run.
  webServer: [
    {
      name: "backend",
      command: "bash e2e/start-backend.sh",
      env: backendEnv(),
      // Any 2xx-403 counts as ready: this is the 401 of a protected endpoint without a token, so
      // the app and its security chain are up (MongoDB is connected at startup too).
      url: `${BACKEND_URL}/auth/users/me`,
      // The first run builds the jar and runs the seeder before the server starts.
      timeout: 300_000,
      reuseExistingServer: false,
    },
    {
      name: "frontend",
      command: `npm run build && npx vite preview --port ${PREVIEW_PORT} --strictPort`,
      env: { SQUADPULSE_PREVIEW_HTTPS: "1", SQUADPULSE_BACKEND_URL: BACKEND_URL },
      url: `${BASE_URL}/app/login`,
      ignoreHTTPSErrors: true,
      timeout: 180_000,
      reuseExistingServer: false,
    },
  ],
});
