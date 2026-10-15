package com.squadpulse.match;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's club's scraped league data, read-only (KAN-66; see {@link MatchReadService}). Needs
 * {@code VIEW_ONLY}. Takes no parameters at all: team, league and season come from the club's own
 * mapping, never from the request. A club without a mapping gets a 200 with {@code NOT_MAPPED}.
 *
 * <p>{@code GET /matches/games/{id}} (KAN-79) takes only our opaque id of a game's details ({@link
 * GameDetailsReadService}); an unknown id, another club's, or one of an earlier mapping is a 404.
 */
@RestController
@RequestMapping("/matches")
class MatchController {

  private final MatchReadService matchReadService;
  private final GameDetailsReadService gameDetailsReadService;

  MatchController(
      MatchReadService matchReadService, GameDetailsReadService gameDetailsReadService) {
    this.matchReadService = matchReadService;
    this.gameDetailsReadService = gameDetailsReadService;
  }

  @GetMapping("/fixtures")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  FixturesResponse fixtures() {
    return matchReadService.fixtures();
  }

  @GetMapping("/league-table")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  LeagueTableResponse leagueTable() {
    return matchReadService.leagueTable();
  }

  @GetMapping("/games/{id}")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  GameDetailsResponse gameDetails(@PathVariable String id) {
    return gameDetailsReadService.read(id);
  }
}
