import { expect, type Page, type Response } from "@playwright/test";
import he from "../src/i18n/locales/he.json" with { type: "json" };
import { E2E_ADMIN } from "./env.ts";

/** The Hebrew UI text for an i18n key, with {{name}} placeholders filled — never hard-coded. */
export function t(key: string, values: Record<string, string | number> = {}): string {
  let node: unknown = he;
  for (const part of key.split(".")) {
    node = (node as Record<string, unknown> | undefined)?.[part];
  }
  if (typeof node !== "string") {
    throw new Error(`No string for i18n key ${key}`);
  }
  return node.replace(/\{\{(\w+)\}\}/g, (_, name: string) => String(values[name]));
}

/** Whether a response is the answer to `METHOD path` (exact path, any query string). */
export function isCall(response: Response, method: string, path: string): boolean {
  return response.request().method() === method && new URL(response.url()).pathname === path;
}

/** Fills the login form with the given credentials and submits it, from the login screen. */
export async function submitLogin(page: Page, password: string) {
  await page.getByLabel(t("auth.fields.email")).fill(E2E_ADMIN.email);
  await page.getByLabel(t("auth.fields.password")).fill(password);
  await page.getByRole("button", { name: t("auth.login.submit") }).click();
}

/** Logs in as the E2E admin through the form and waits for the shell on the dashboard. */
export async function logIn(page: Page) {
  await page.goto("/app/login");
  await submitLogin(page, E2E_ADMIN.password);
  await expect(page).toHaveURL(/\/app$/);
  await expectShell(page);
}

/** The app shell: the main navigation and the top bar with the user. */
export async function expectShell(page: Page) {
  await expect(page.getByRole("navigation", { name: t("shell.navLabel") })).toBeVisible();
  await expect(page.getByRole("button", { name: t("shell.logout") })).toBeVisible();
  await expect(page.getByText(E2E_ADMIN.fullName)).toBeVisible();
}

/** Opens the squad screen through the sidebar. */
export async function openSquad(page: Page) {
  await page
    .getByRole("navigation", { name: t("shell.navLabel") })
    .getByRole("link", { name: t("nav.squad") })
    .click();
  await expect(page).toHaveURL(/\/app\/squad$/);
}
