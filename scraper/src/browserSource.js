// football.org.il through headless Chromium, used as an HTTP client only (KAN-64 findings,
// "Verification of the recommended setup"). No stealth, no proxies, no header changes beyond the
// honest User-Agent and the locale.

import { chromium, errors } from "playwright";
import pkg from "../package.json" with { type: "json" };
import { SourceNetworkError, SourceTimeoutError } from "./sourceErrors.js";

export const SOURCE_ORIGIN = "https://www.football.org.il/";
export const USER_AGENT = `SquadPulse-scraper/${pkg.version} (+https://github.com/Galaviv15/Squad-Pulse)`;

/**
 * `origin` is a parameter only so tests can point it at a local server; production always uses
 * the default (deliberately not configurable through the environment).
 */
export function createBrowserSource({ origin = SOURCE_ORIGIN, browserType = chromium } = {}) {
  return {
    origin,
    async open(signal) {
      const browser = await browserType.launch({ headless: true });
      const closeOnAbort = () => browser.close().catch(() => {});
      signal?.addEventListener("abort", closeOnAbort, { once: true });
      let context;
      let page;
      // The exact LeagueTable URL we're about to fetch ourselves; no other fetch is let through.
      let allowedFetch = null;
      try {
        context = await browser.newContext({ userAgent: USER_AGENT, locale: "he-IL" });
        // On the context, so it also covers a page opened to replace a timed-out one.
        await context.route("**/*", (route) => {
          const request = route.request();
          const allowed =
            (request.resourceType() === "document" && request.url().startsWith(origin)) ||
            (request.resourceType() === "fetch" &&
              allowedFetch !== null &&
              request.url() === allowedFetch);
          return allowed ? route.continue() : route.abort();
        });
        page = await context.newPage();
      } catch (error) {
        signal?.removeEventListener("abort", closeOnAbort);
        await browser.close();
        throw error;
      }

      return {
        async navigate(url, timeoutMs) {
          let response;
          try {
            response = await page.goto(url, { waitUntil: "domcontentloaded", timeout: timeoutMs });
          } catch (error) {
            if (error instanceof errors.TimeoutError) {
              // The timed-out navigation is still pending and would abort a retry on this page.
              await page.close().catch(() => {});
              page = await context.newPage();
              throw new SourceTimeoutError();
            }
            throw new SourceNetworkError();
          }
          if (response === null) throw new SourceNetworkError();
          return { status: response.status(), body: await response.text() };
        },

        async fetchInPage(url, timeoutMs) {
          allowedFetch = url;
          let result;
          try {
            result = await page.evaluate(
              async ({ url, timeoutMs }) => {
                const controller = new AbortController();
                const timer = setTimeout(() => controller.abort(), timeoutMs);
                try {
                  const response = await fetch(url, {
                    headers: { "x-requested-with": "XMLHttpRequest" },
                    signal: controller.signal,
                  });
                  return { status: response.status, body: await response.text() };
                } catch {
                  return { error: controller.signal.aborted ? "timeout" : "network" };
                } finally {
                  clearTimeout(timer);
                }
              },
              { url, timeoutMs },
            );
          } catch {
            throw new SourceNetworkError();
          } finally {
            allowedFetch = null;
          }
          if (result.error === "timeout") throw new SourceTimeoutError();
          if (result.error) throw new SourceNetworkError();
          return result;
        },

        async close() {
          signal?.removeEventListener("abort", closeOnAbort);
          await browser.close();
        },
      };
    },
  };
}
