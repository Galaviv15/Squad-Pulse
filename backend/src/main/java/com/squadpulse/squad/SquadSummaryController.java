package com.squadpulse.squad;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A read-only summary of the caller's own club's squad, for the squad screen and dashboard (see
 * {@link PlayerService#summary()}). Needs {@code VIEW_ONLY}; no clubId is ever read from the
 * request.
 *
 * <p>Counts <b>active</b> players only (whatever their medical status); released players never
 * count. Each player is counted once, in the {@link Line} of their primary position. The average
 * age is the mean of each player's exact age today — completed years plus the elapsed fraction of
 * the current birthday year — rounded half up to one decimal. Players without a date of birth are
 * counted but left out of the average, which is {@code null} if no player has one (including an
 * empty squad).
 *
 * <p>Not {@code @Validated}, like {@link PlayerController} — it has no parameters to validate.
 */
@RestController
@RequestMapping("/squad/summary")
class SquadSummaryController {

  private final PlayerService playerService;

  SquadSummaryController(PlayerService playerService) {
    this.playerService = playerService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  SquadSummaryResponse summary() {
    return playerService.summary();
  }
}
