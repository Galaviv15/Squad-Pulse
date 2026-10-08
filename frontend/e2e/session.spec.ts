import { expect, request, test, type Response } from "@playwright/test";
import { ACCESS_TTL_SECONDS, BASE_URL, DISTINCTIVE_PLAYER } from "./env.ts";
import { isCall, logIn, openSquad, t } from "./helpers.ts";

test("the refresh cookie is stored as sent, and a reload keeps the session", async ({
  page,
  context,
}) => {
  await logIn(page);
  await openSquad(page);

  // Stored by this browser over HTTPS, with every attribute the backend sets.
  const cookie = (await context.cookies()).find((c) => c.name === "refresh_token");
  expect(cookie).toMatchObject({
    domain: "localhost",
    path: "/auth",
    secure: true,
    httpOnly: true,
    sameSite: "Strict",
  });

  // ...and sent: the bootstrap's refresh succeeds only with it.
  const refresh = page.waitForResponse((r) => isCall(r, "POST", "/auth/refresh"));
  await page.reload();
  expect((await refresh).status()).toBe(200);
  await expect(page).toHaveURL(/\/app\/squad$/);
  await expect(page.getByRole("link", { name: DISTINCTIVE_PLAYER })).toBeVisible();
  await expect(page.getByRole("heading", { name: t("auth.login.title") })).toHaveCount(0);
});

test("an expired access token is refreshed silently", async ({ page }) => {
  // The dashboard's summary is its last request: once it's answered, nothing is in flight.
  const summary = page.waitForResponse((r) => isCall(r, "GET", "/squad/summary"));
  await logIn(page);
  expect((await summary).status()).toBe(200);

  // Waiting on time is the point here: the access token must really expire on the server (no
  // faked 401). The margin covers the token's whole-second expiry.
  await page.waitForTimeout((ACCESS_TTL_SECONDS + 2) * 1000);

  const calls: Response[] = [];
  page.on("response", (response) => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith("/squad/") || path === "/auth/refresh") {
      calls.push(response);
    }
  });
  await openSquad(page);
  await expect(page.getByRole("link", { name: DISTINCTIVE_PLAYER })).toBeVisible();

  // In order: the list request with the expired token (401), one refresh (200), the resent list
  // request (200).
  expect(calls.map((r) => `${new URL(r.url()).pathname} ${r.status()}`)).toEqual([
    "/squad/players 401",
    "/auth/refresh 200",
    "/squad/players 200",
  ]);
  await expect(page).toHaveURL(/\/app\/squad$/);
});

test("logout ends the session in the browser and on the server", async ({ page, context }) => {
  await logIn(page);
  const oldCookie = (await context.cookies()).find((c) => c.name === "refresh_token")?.value;
  expect(oldCookie).toBeTruthy();

  await page.getByRole("button", { name: t("shell.logout") }).click();
  await expect(page).toHaveURL(/\/app\/login$/);

  await page.reload();
  await expect(page.getByRole("heading", { name: t("auth.login.title") })).toBeVisible();
  await expect(page).toHaveURL(/\/app\/login$/);

  // The real check: the server revoked the token, so presenting it again is refused. A request
  // without the cookie would be a 400 (missing cookie), so the 401 also shows it was sent.
  const api = await request.newContext({ baseURL: BASE_URL, ignoreHTTPSErrors: true });
  try {
    const refresh = await api.post("/auth/refresh", {
      headers: { Cookie: `refresh_token=${oldCookie}` },
    });
    expect(refresh.status()).toBe(401);
  } finally {
    await api.dispose();
  }
});
