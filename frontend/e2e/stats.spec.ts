import { expect, test } from "@playwright/test";
import { isCall, logIn, t } from "./helpers.ts";

/*
 * The player statistics screen (KAN-74) against the real backend, for the E2E club, which stays
 * unmapped to football.org.il (see matches.spec.ts): the real GET /matches/player-stats answers
 * NOT_MAPPED, so this checks the response end to end without any scraping.
 */
test("an unmapped club sees the not-mapped state on the stats screen, with no refresh or link controls", async ({
  page,
}) => {
  await logIn(page);
  const stats = page.waitForResponse((r) => isCall(r, "GET", "/matches/player-stats"));

  await page
    .getByRole("navigation", { name: t("shell.navLabel") })
    .getByRole("link", { name: t("nav.stats") })
    .click();

  await expect(page).toHaveURL(/\/app\/stats$/);
  expect((await stats).status()).toBe(200);
  await expect(page.getByRole("heading", { level: 1, name: t("nav.stats") })).toBeVisible();
  const main = page.getByRole("main");
  await expect(main.getByText(t("matches.notMapped"))).toBeVisible();
  await expect(main.getByRole("button", { name: t("matches.refresh.button") })).toHaveCount(0);
  await expect(main.getByRole("table")).toHaveCount(0);
  await expect(main.getByRole("radiogroup", { name: t("stats.filter.label") })).toHaveCount(0);
  await expect(main.getByRole("button", { name: /קישור/ })).toHaveCount(0);
});

/*
 * KAN-77's create-player route, opened directly, for the same unmapped club: the real read answers
 * NOT_MAPPED, so the row can't be found and the page says so, with no form.
 */
test("the create-player route for an unknown row says the player is gone, with no form", async ({
  page,
}) => {
  await logIn(page);
  const stats = page.waitForResponse((r) => isCall(r, "GET", "/matches/player-stats"));

  await page.goto("/app/stats/some-id/new-player");

  expect((await stats).status()).toBe(200);
  await expect(
    page.getByRole("heading", { level: 1, name: t("stats.createPlayer.title") }),
  ).toBeVisible();
  const main = page.getByRole("main");
  await expect(main.getByText(t("stats.createPlayer.rowGone"))).toBeVisible();
  await expect(
    main.getByRole("link", { name: t("stats.createPlayer.backToStats") }),
  ).toHaveAttribute("href", "/app/stats");
  await expect(main.getByRole("textbox")).toHaveCount(0);
  await expect(main.getByRole("button", { name: t("squad.form.submitCreate") })).toHaveCount(0);
});
