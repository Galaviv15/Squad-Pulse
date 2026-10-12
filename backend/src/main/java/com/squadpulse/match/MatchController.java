package com.squadpulse.match;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's club's scraped league data, read-only (KAN-66; see {@link MatchReadService}). Needs
 * {@code VIEW_ONLY}. Takes no parameters at all: team, league and season come from the club's own
 * mapping, never from the request. A club without a mapping gets a 200 with {@code NOT_MAPPED}.
 */
@RestController
@RequestMapping("/matches")
class MatchController {

  private final MatchReadService matchReadService;

  MatchController(MatchReadService matchReadService) {
    this.matchReadService = matchReadService;
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
}
