/**
 * Match and league domain data: the league table, fixtures, and per-game lineups (our side's
 * players with the shirt number worn, goals, cards and substitutions; no playing minutes) — the
 * data itself and the logic around it. Data reaches this module from two sources, scraped (via
 * {@code scrapingintegration}) and entered by hand, and both end up here.
 *
 * <p>Club data goes through the central {@code clubId} isolation layer in {@code common}, like the
 * scraped fixtures ({@code LeagueFixturesSnapshot}). The one exception is a league's standings
 * ({@code LeagueStandingsSnapshot}, KAN-66): the same public table for every club in the league, so
 * stored once and deliberately {@code @NotClubScoped}, under the rules on that class. Scraped data
 * is kept as snapshots carrying when they were fetched, shown as "last updated at" (see
 * docs/spec.md section 07). Which team, league and season a club sees comes only from its own
 * mapping ({@code auth.ClubFootballMappings}), never from a request.
 *
 * <p>Player season statistics (KAN-72) are club-scoped too, with no new exception: a snapshot per
 * club, team and season ({@code PlayerSeasonStatsSnapshot}), written only by the ingest, and the
 * club's links from a scraped player to one of its own {@code squad.Player}s ({@code
 * PlayerStatsLink}), with name suggestions computed on read ({@code NameSuggestions}). The ingest
 * never writes a {@code Player}, and football.org.il's {@code player_id} never leaves the backend:
 * responses carry an opaque id of ours. {@code match} depends on {@code squad} (to read players);
 * {@code squad} sees the links only through its own {@code squad.PlayerStatsLinks} interface,
 * implemented here, so there is no cycle.
 *
 * <p>Game details (KAN-79) are club-scoped too, with no new exception: one {@code GameDetails}
 * record per club, team, season and game — <b>our side only</b> (the opponent is a name and a
 * score), written only by the ingest, per game and never older over newer, with an opaque id of
 * ours kept across replaces — and a {@code GameDetailAttempts} count per game the scraper failed
 * on, which gives a game up after three broken pages, for three days from the last one. The
 * per-game shirt number lives only on the appearance: nothing here writes a {@code Player} or its
 * jersey number, and no football.org.il id leaves the backend. {@code KnownGames} lists the stored
 * and given-up games for {@code scrapingintegration}'s trigger; appearances reach the club's
 * players through the KAN-72 links.
 *
 * <p>See docs/spec.md section 07 (Matches, stats &amp; scraping). Tracked in Jira under KAN-14
 * (Match &amp; Scraping Integration).
 */
package com.squadpulse.match;
