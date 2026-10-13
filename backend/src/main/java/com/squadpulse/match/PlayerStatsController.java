package com.squadpulse.match;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's club's scraped player season statistics and their links to its own players (KAN-72;
 * see {@link PlayerStatsReadService} and {@link PlayerStatsLinkService}). Reading needs {@code
 * VIEW_ONLY}, linking and unlinking {@code EDIT_FULL}. Team and season come from the club's own
 * mapping, never from the request; {@code {id}} is our opaque row id, never a football.org.il id.
 */
@RestController
@RequestMapping("/matches/player-stats")
class PlayerStatsController {

  private final PlayerStatsReadService readService;
  private final PlayerStatsLinkService linkService;

  PlayerStatsController(PlayerStatsReadService readService, PlayerStatsLinkService linkService) {
    this.readService = readService;
    this.linkService = linkService;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  PlayerStatsResponse read() {
    return readService.read();
  }

  /**
   * Links the scraped player to one of the club's players, replacing its link: 200 with the row.
   * 404 if either isn't the club's; 409 {@code PLAYER_ALREADY_LINKED} if that player is linked to
   * another scraped player.
   */
  @PutMapping("/{id}/link")
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  PlayerStatsResponse.Row link(
      @PathVariable String id, @Valid @RequestBody LinkPlayerRequest request) {
    return linkService.link(id, request.playerId());
  }

  /** Removes the scraped player's link: 204, also if there was none; 404 for an unknown id. */
  @DeleteMapping("/{id}/link")
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  ResponseEntity<Void> unlink(@PathVariable String id) {
    linkService.unlink(id);
    return ResponseEntity.noContent().build();
  }
}
