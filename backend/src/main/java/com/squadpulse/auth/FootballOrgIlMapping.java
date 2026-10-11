package com.squadpulse.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Which team, league and season a {@link Club} is on football.org.il (KAN-65): the ids the scraper
 * needs to know what to fetch. Stored embedded in the club document as {@code footballOrgIl:
 * {teamId, leagueId, seasonId}}.
 *
 * <p>All or nothing: a club either has no mapping ({@code null}: not mapped, so scraping is
 * unavailable for it) or all three ids. The constraints aren't checked on save (Bean Validation
 * never runs on entities there), so every write path validates first — {@link ClubBootstrapService}
 * does: the {@link Club} (whose field is {@code @Valid}) on create, this record itself on update —
 * the same constraints either way.
 *
 * <p>The season changes every summer, and the league on promotion or relegation (possibly every
 * season), so the system owner updates all three together each year; the team id looks stable, but
 * that's unverified (scraper/docs/KAN-64-findings.md).
 */
record FootballOrgIlMapping(
    @NotNull @Positive Integer teamId,
    @NotNull @Positive Integer leagueId,
    @NotNull @Positive Integer seasonId) {

  /** For the bootstrap's log lines: the three ids, nothing else. */
  @Override
  public String toString() {
    return "teamId=" + teamId + ", leagueId=" + leagueId + ", seasonId=" + seasonId;
  }
}
