package com.squadpulse.match;

/**
 * One row of a {@link LeagueStandingsSnapshot}, as published: goals for / against (the scraper
 * fixes the site's against-for order), and {@link #points} as the site shows them, never recomputed
 * (deductions are possible). The goal difference isn't stored; it's derived when read.
 */
record StoredStandingsRow(
    int position,
    int teamId,
    String teamName,
    int played,
    int won,
    int drawn,
    int lost,
    int goalsFor,
    int goalsAgainst,
    int points) {}
