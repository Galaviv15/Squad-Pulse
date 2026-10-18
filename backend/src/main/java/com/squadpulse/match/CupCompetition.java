package com.squadpulse.match;

/**
 * A cup competition whose games the scraper reads from the team's cup lists on football.org.il
 * (KAN-84), part of the scraper ↔ backend contract (docs/scraper-backend-contract.md). Only the
 * State Cup today (its list already includes the regional preliminary rounds); designed to grow —
 * the regional cup is next (KAN-87). There's deliberately no Toto Cup: Liga Alef and below don't
 * play one.
 *
 * <p>A value added here doesn't compile until {@link FixturesResponse.Competition#of} maps it.
 */
public enum CupCompetition {
  /** גביע המדינה: {@code GetTeamNatCupData}. */
  STATE_CUP
}
