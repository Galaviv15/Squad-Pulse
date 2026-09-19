/**
 * The integration boundary with the separate Node.js scraper worker (see the {@code scraper/}
 * project): triggers it, receives what it scraped from football.org.il, and feeds that into the
 * {@code match} module.
 *
 * <p>This module only talks to the scraper worker and hands the results to {@code match}. It does
 * <b>not</b> own any domain logic or domain data — the league table, fixtures, and lineups belong
 * to {@code match}.
 *
 * <p>See docs/spec.md section 07 (Matches, stats &amp; scraping). Tracked in Jira under KAN-14
 * (Match &amp; Scraping Integration).
 */
package com.squadpulse.scrapingintegration;
