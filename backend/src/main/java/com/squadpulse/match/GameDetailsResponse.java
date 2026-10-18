package com.squadpulse.match;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * {@code GET /matches/games/{id}} (KAN-79): one played game's details, from our team's point of
 * view, <b>our side only</b> — the opponent is a name and a score. Never carries a football.org.il
 * id of any kind (no {@code game_id}, {@code player_id}, team, league or season; docs/spec.md
 * section 09): {@code id} is our own opaque one.
 *
 * @param kickoffTime {@code HH:mm}, {@code null} if the site gave none
 * @param competition as the page's heading shows it, e.g. {@code ליגה א' דרום}
 * @param result from our side; {@code null} for a cup game whose cup list shows extra time or
 *     penalties (KAN-84, as on {@code GET /matches/fixtures}); the scores are still shown
 * @param lastUpdatedAt when the scraper fetched the page
 * @param players starters, then substitutes who came on, then unused; within a role, as sent
 */
public record GameDetailsResponse(
    String id,
    LocalDate date,
    @JsonFormat(pattern = "HH:mm") LocalTime kickoffTime,
    String competition,
    int round,
    FixturesResponse.Side ownSide,
    String opponentName,
    int ownScore,
    int opponentScore,
    FixturesResponse.Result result,
    Instant lastUpdatedAt,
    List<Appearance> players) {

  /**
   * One of our players in this game.
   *
   * @param name as the site shows it (first name first); {@code null} if the site masks it
   * @param linkedPlayer the club's own player this one is linked to (the KAN-72 links), or {@code
   *     null}; a link to a deleted player counts as none
   * @param shirtNumber worn in this game, never the squad's jersey number; {@code null} if missing
   * @param events by minute, an unknown minute ({@code null}) last
   */
  public record Appearance(
      String name,
      PlayerStatsResponse.PlayerRef linkedPlayer,
      Integer shirtNumber,
      ScrapedGameDetails.Role role,
      boolean goalkeeper,
      boolean captain,
      List<Event> events) {}

  /**
   * @param minute as the site shows it (93, not 90+3); {@code null} if unknown
   */
  public record Event(ScrapedGameDetails.EventKind kind, Integer minute) {}
}
