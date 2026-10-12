/**
 * The integration boundary with the separate Node.js scraper worker (see the {@code scraper/}
 * project): triggers it, receives what it scraped from football.org.il, and feeds that into the
 * {@code match} module.
 *
 * <p>This module only talks to the scraper worker and hands the results to {@code match}. It does
 * <b>not</b> own any domain logic or domain data — the league table, fixtures, and lineups belong
 * to {@code match}.
 *
 * <p>The scraper posts each scrape to {@code POST /internal/scrape-results} (KAN-66), authenticated
 * by a service token in its own security chain ({@code InternalSecurityConfig}), never by a user's
 * access token. Its request and response records are the scraper ↔ backend contract, described in
 * docs/scraper-backend-contract.md.
 *
 * <p>The other direction (KAN-68): {@code POST /matches/refresh} ({@code EDIT_FULL}) asks the
 * scraper to scrape the caller's club ({@code ScrapeTriggerService}, over {@code ScraperClient}),
 * behind per-team and per-league Redis locks — the primary protection of football.org.il — and
 * {@code GET /matches/refresh} shows the club's latest run, whose status the ingest and a lazy
 * timeout set ({@code ScrapeRunStatusService}).
 *
 * <p>See docs/spec.md section 07 (Matches, stats &amp; scraping). Tracked in Jira under KAN-14
 * (Match &amp; Scraping Integration).
 */
package com.squadpulse.scrapingintegration;
