package com.squadpulse.match;

import com.squadpulse.squad.CreatePlayerRequest;
import com.squadpulse.squad.Player;
import com.squadpulse.squad.PlayerResponse;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The caller's club's scraped player season statistics and their links to its own players (KAN-72;
 * see {@link PlayerStatsReadService} and {@link PlayerStatsLinkService}). Reading needs {@code
 * VIEW_ONLY}, linking, unlinking and creating a player from a row (KAN-77, {@link
 * StatsPlayerCreationService}) {@code EDIT_FULL}. Team and season come from the club's own mapping,
 * never from the request; {@code {id}} is our opaque row id, never a football.org.il id.
 */
@RestController
@RequestMapping("/matches/player-stats")
class PlayerStatsController {

  private final PlayerStatsReadService readService;
  private final PlayerStatsLinkService linkService;
  private final StatsPlayerCreationService creationService;

  PlayerStatsController(
      PlayerStatsReadService readService,
      PlayerStatsLinkService linkService,
      StatsPlayerCreationService creationService) {
    this.readService = readService;
    this.linkService = linkService;
    this.creationService = creationService;
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

  /**
   * Creates a squad player from the body — exactly {@code POST /squad/players}'s body, validated
   * the same way — and links the scraped player to it, in one transaction: 201 with the player as
   * {@code POST /squad/players} answers it, {@code Location} = the player's URL. 404 if the scraped
   * player isn't the club's; 409 {@code STATS_ROW_ALREADY_LINKED} if it's linked to an existing
   * player, {@code JERSEY_NUMBER_TAKEN}, or {@code CONCURRENT_MODIFICATION} for a lost race —
   * nothing written in any of them.
   */
  @PostMapping("/{id}/player")
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  ResponseEntity<PlayerResponse> createPlayer(
      @PathVariable String id, @Valid @RequestBody CreatePlayerRequest request) {
    Player player = creationService.createLinked(id, request);
    // The player's own URL, not one under this request's path (fromCurrentRequest would give
    // /matches/player-stats/{id}/player/{playerId}).
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/squad/players/{id}")
            .buildAndExpand(player.getId())
            .toUri();
    return ResponseEntity.created(location).body(PlayerResponse.ofNew(player));
  }
}
