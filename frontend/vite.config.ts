import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";
import { DEV_CERT_DIR, devHttpsOptions } from "./devHttps.ts";

/**
 * The backend's top-level path prefixes, proxied by the dev server so the SPA calls the API on
 * its own origin (no CORS). A new backend prefix must be added here. SPA routes live under
 * /app, so they never collide with these on a browser reload.
 */
const BACKEND_PREFIXES = ["auth", "squad", "clubs", "users"];

// A key starting with "^" is a regex tested against the request URL (path + query); a plain key
// would be a string prefix, so "/auth" would also catch "/authors". This matches whole segments.
const BACKEND_PATHS = `^/(${BACKEND_PREFIXES.join("|")})(/|\\?|$)`;

// https://vitejs.dev/config/
export default defineConfig(({ command, mode, isPreview }) => {
  // Not VITE_-prefixed, so it never reaches client code.
  const env = loadEnv(mode, process.cwd(), "");
  const backendUrl = env.SQUADPULSE_BACKEND_URL || "http://localhost:8080";
  // Only `vite` / `npm run dev` itself needs the certificate. Vitest loads this config as a
  // "serve" too (and sets VITEST), and `vite preview` is a "serve" with isPreview; neither, nor a
  // build, may require a certificate (CI has none).
  const isDevServer = command === "serve" && !isPreview && !process.env.VITEST;

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    server: {
      port: 5173,
      // HTTPS only, no http fallback: a missing certificate fails the start (see devHttps.ts).
      https: isDevServer ? devHttpsOptions(DEV_CERT_DIR) : undefined,
      // Vite's default CORS policy allows any localhost origin and adds
      // Access-Control-Allow-Origin / Vary: Origin to every answer, proxied ones too. The SPA
      // calls the API on its own origin, so dev matches production: no CORS at all.
      cors: false,
      proxy: {
        // No path or cookie rewriting: the refresh cookie (Path=/auth, no Domain) must reach the
        // browser unchanged. changeOrigin stays false so the backend sees the dev server's Host,
        // as it will behind a production reverse proxy; e.g. the Location header of
        // POST /squad/players is built from it and must point at this origin. xfwd adds
        // X-Forwarded-For / -Port / -Proto / -Host, so the backend's "dev" profile (which alone
        // trusts them) builds https://localhost:5173/... URLs over this plain-http hop.
        [BACKEND_PATHS]: {
          target: backendUrl,
          changeOrigin: false,
          xfwd: true,
        },
      },
    },
    test: {
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
    },
  };
});
