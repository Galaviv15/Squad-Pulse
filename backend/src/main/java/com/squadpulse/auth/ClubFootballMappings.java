package com.squadpulse.auth;

import com.squadpulse.common.ClubContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The one public, read-only view of the clubs' football.org.il mappings (KAN-66), for {@code match}
 * and {@code scrapingintegration}, which must not reach into {@code auth}'s internals ({@link
 * FootballOrgIlMapping} stays package-private). It offers only what those modules need; it never
 * writes, and the mapping it reads is set only by the system owner's bootstrap (KAN-65).
 *
 * <p>Two of its lookups run across all clubs ({@link #clubIdsMappedTo}, {@link #anyClubMappedTo}):
 * they serve the scraper's ingest, which has no club context and must find which clubs a scrape
 * belongs to — from the ids it scraped, never from a clubId in the request (CLAUDE.md standing rule
 * 4). They return clubIds or a boolean to server-side code only; nothing tenant-owned is returned,
 * and the mapping itself never appears in an API response (docs/spec.md section 09).
 *
 * <p><b>Confined:</b> those two may be called only from {@code match.ScrapeIngestService}, and the
 * {@link ClubRepository} lookups behind them only from this class ({@code
 * CrossClubLookupConfinementTest}) — so no user-facing path can find another club's id and {@code
 * callAs} into it. {@link #currentClubMapping()} is unrestricted: it's scoped by {@link
 * ClubContext}.
 */
@Service
public class ClubFootballMappings {

  private final ClubRepository clubRepository;
  private final ClubContext clubContext;

  ClubFootballMappings(ClubRepository clubRepository, ClubContext clubContext) {
    this.clubRepository = clubRepository;
    this.clubContext = clubContext;
  }

  /**
   * The mapping of the current club — the one in {@link ClubContext}, so a caller can't ask about
   * another club — or empty if it isn't mapped (or doesn't exist). Throws without a club context.
   */
  public Optional<FootballOrgIlIds> currentClubMapping() {
    return clubRepository
        .findById(clubContext.requireClubId())
        .map(Club::getFootballOrgIl)
        .map(FootballOrgIlIds::of);
  }

  /**
   * The ids of the clubs currently mapped to exactly this team, league and season: normally one,
   * none if the mapping changed since the scrape started. For the scraper's ingest, which then
   * writes each club's fixtures inside {@link ClubContext#callAs} with an id from this list.
   */
  public List<String> clubIdsMappedTo(int teamId, int leagueId, int seasonId) {
    return clubRepository.findIdsByFootballOrgIl(teamId, leagueId, seasonId).stream()
        .map(Club::getId)
        .toList();
  }

  /** Whether any club is currently mapped to this league and season, whatever its team. */
  public boolean anyClubMappedTo(int leagueId, int seasonId) {
    return clubRepository.existsByFootballOrgIlLeague(leagueId, seasonId);
  }
}
