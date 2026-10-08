import { expect, test } from "@playwright/test";
import { DISTINCTIVE_PLAYER, E2E_PLAYERS } from "./env.ts";
import { logIn, openSquad, t } from "./helpers.ts";

test("login leads into the shell, and the squad screen lists the seeded players", async ({
  page,
}) => {
  await logIn(page);
  await openSquad(page);

  await expect(page.getByRole("link", { name: DISTINCTIVE_PLAYER })).toBeVisible();
  await expect(page.getByText(t("squad.count_other", { count: E2E_PLAYERS.length }))).toBeVisible();
  // The table's body rows: every row but the header one.
  await expect(page.getByRole("row")).toHaveCount(E2E_PLAYERS.length + 1);
});
