import { expect, test } from "@playwright/test";
import { isCall, submitLogin, t } from "./helpers.ts";

test("a wrong password shows the error and stays on the login screen", async ({ page }) => {
  await page.goto("/app/login");
  const login = page.waitForResponse((r) => isCall(r, "POST", "/auth/login"));
  await submitLogin(page, "not-the-password");

  expect((await login).status()).toBe(401);
  await expect(page.getByRole("alert")).toHaveText(t("auth.errors.wrongCredentials"));
  await expect(page).toHaveURL(/\/app\/login$/);
  await expect(page.getByRole("heading", { name: t("auth.login.title") })).toBeVisible();
});
