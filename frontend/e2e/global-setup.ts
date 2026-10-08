import { request } from "@playwright/test";
import { ACCESS_TTL_SECONDS, BASE_URL, E2E_ADMIN, E2E_PLAYERS } from "./env.ts";

/**
 * Runs once both servers are up (the backend has already been seeded with the club and its
 * admin): creates the players through the real API, via the preview's proxy, so they go through
 * the same auth and club isolation as the app's own requests.
 */
export default async function globalSetup() {
  const api = await request.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
  try {
    const login = await api.post("/auth/login", {
      data: { email: E2E_ADMIN.email, password: E2E_ADMIN.password },
    });
    if (login.status() !== 200) {
      throw new Error(`E2E setup: admin login answered ${login.status()}: ${await login.text()}`);
    }
    const { accessToken, expiresIn } = (await login.json()) as {
      accessToken: string;
      expiresIn: number;
    };
    // Proves the short TTL reached the backend (an env var the server could silently ignore).
    if (expiresIn !== ACCESS_TTL_SECONDS) {
      throw new Error(`E2E setup: expected expiresIn ${ACCESS_TTL_SECONDS}, got ${expiresIn}`);
    }

    for (const player of E2E_PLAYERS) {
      const created = await api.post("/squad/players", {
        headers: { Authorization: `Bearer ${accessToken}` },
        data: player,
      });
      if (created.status() !== 201) {
        throw new Error(
          `E2E setup: creating ${player.fullName} answered ${created.status()}: ${await created.text()}`,
        );
      }
    }
  } finally {
    await api.dispose();
  }
}
