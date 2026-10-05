import { fileURLToPath, URL } from "node:url";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";

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
export default defineConfig(({ mode }) => {
  // Not VITE_-prefixed, so it never reaches client code.
  const env = loadEnv(mode, process.cwd(), "");
  const backendUrl = env.SQUADPULSE_BACKEND_URL || "http://localhost:8080";

  return {
    plugins: [react(), tailwindcss()],
    resolve: {
      alias: {
        "@": fileURLToPath(new URL("./src", import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        // No path or cookie rewriting: the refresh cookie (Path=/auth, no Domain) must reach the
        // browser unchanged. changeOrigin stays false so the backend sees the dev server's Host,
        // as it will behind a production reverse proxy; e.g. the Location header of
        // POST /squad/players is built from it and must point at this origin.
        [BACKEND_PATHS]: {
          target: backendUrl,
          changeOrigin: false,
        },
      },
    },
    test: {
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
    },
  };
});
