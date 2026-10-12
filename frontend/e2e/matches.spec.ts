import { expect, test } from "@playwright/test";
import { isCall, logIn, t } from "./helpers.ts";

/*
 * The matches screens against the real backend, for the E2E club, which is deliberately not mapped
 * to football.org.il: the backend jar reads the repo-root .env, which may hold a real SCRAPER_URL,
 * so a mapped club plus a running local scraper could make a test run ask the real site. This
 * checks the real response shape end to end without any scraping.
 */
test("an unmapped club sees the not-mapped state on the dashboard and the matches screen, and no refresh", async ({
  page,
}) => {
  const fixtures = page.waitForResponse((r) => isCall(r, "GET", "/matches/fixtures"));
  const table = page.waitForResponse((r) => isCall(r, "GET", "/matches/league-table"));
  const statusRequests: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/matches/refresh") {
      statusRequests.push(request.method());
    }
  });
  await logIn(page);
  expect((await fixtures).status()).toBe(200);
  expect((await table).status()).toBe(200);

  const main = page.getByRole("main");
  const nextMatch = main.getByRole("heading", { level: 2, name: t("dashboard.nextMatch.title") });
  const leagueTable = main.getByRole("heading", {
    level: 2,
    name: t("dashboard.leagueTable.title"),
  });
  await expect(nextMatch).toBeVisible();
  await expect(leagueTable).toBeVisible();
  await expect(main.getByText(t("matches.notMapped"))).toHaveCount(2);

  await page
    .getByRole("navigation", { name: t("shell.navLabel") })
    .getByRole("link", { name: t("nav.matches") })
    .click();
  await expect(page).toHaveURL(/\/app\/matches$/);
  await expect(page.getByRole("heading", { level: 1, name: t("nav.matches") })).toBeVisible();
  await expect(main.getByText(t("matches.notMapped"))).toBeVisible();

  await page.getByRole("radio", { name: t("matches.tabs.table") }).click();
  await expect(page).toHaveURL(/\/app\/matches\?tab=table$/);
  await expect(main.getByText(t("matches.notMapped"))).toBeVisible();

  // An admin's control asks for the status, which says "not mapped": no button.
  await expect.poll(() => statusRequests).toContain("GET");
  await expect(page.getByRole("button", { name: t("matches.refresh.button") })).toHaveCount(0);
  expect(statusRequests).not.toContain("POST");
});
