/**
 * Match and league domain data: the league table, fixtures, and lineups (with jersey numbers and
 * playing minutes) — the data itself and the logic around it. Data reaches this module from two
 * sources, scraped (via {@code scrapingintegration}) and entered by hand, and both end up here.
 *
 * <p>Every document is tenant data, so all access goes through the central {@code clubId} isolation
 * layer in {@code common}. Scraped values should carry a "last updated at" timestamp (see
 * docs/spec.md section 07).
 *
 * <p>See docs/spec.md section 07 (Matches, stats &amp; scraping). Tracked in Jira under KAN-14
 * (Match &amp; Scraping Integration).
 */
package com.squadpulse.match;
