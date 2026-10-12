/**
 * Match and league domain data: the league table, fixtures, and lineups (with jersey numbers and
 * playing minutes) — the data itself and the logic around it. Data reaches this module from two
 * sources, scraped (via {@code scrapingintegration}) and entered by hand, and both end up here.
 *
 * <p>Club data goes through the central {@code clubId} isolation layer in {@code common}, like the
 * scraped fixtures ({@code LeagueFixturesSnapshot}). The one exception is a league's standings
 * ({@code LeagueStandingsSnapshot}, KAN-66): the same public table for every club in the league, so
 * stored once and deliberately {@code @NotClubScoped}, under the rules on that class. Scraped data
 * is kept as snapshots carrying when they were fetched, shown as "last updated at" (see
 * docs/spec.md section 07). Which team, league and season a club sees comes only from its own
 * mapping ({@code auth.ClubFootballMappings}), never from a request.
 *
 * <p>See docs/spec.md section 07 (Matches, stats &amp; scraping). Tracked in Jira under KAN-14
 * (Match &amp; Scraping Integration).
 */
package com.squadpulse.match;
