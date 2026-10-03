package com.squadpulse.squad;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The club's roster (see {@link PlayerService}). Reading needs {@code VIEW_ONLY}; creating,
 * editing, releasing and re-activating {@code EDIT_FULL}; permanently deleting {@code ADMIN}.
 * Always the caller's own club: no clubId is ever read from the request.
 *
 * <p>Deliberately not {@code @Validated}: that would move the {@code @Min}/{@code @Max} checks on
 * the query parameters from Spring MVC's built-in method validation (a 400 via {@code
 * HandlerMethodValidationException}) to the AOP one, whose {@code ConstraintViolationException}
 * isn't mapped and would be a 500.
 */
@RestController
@RequestMapping("/squad/players")
class PlayerController {

  private final PlayerService playerService;

  PlayerController(PlayerService playerService) {
    this.playerService = playerService;
  }

  @InitBinder
  void registerPlayerStatusEditor(WebDataBinder binder) {
    binder.registerCustomEditor(PlayerStatus.class, new PlayerStatus.Editor());
  }

  /**
   * The club's players, filtered (all filters combined with AND) and in squad order: primary
   * position (GK → ST), then jersey number (none last), then name. Not paginated.
   */
  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  List<PlayerResponse> list(
      @RequestParam(defaultValue = "active") PlayerStatus status,
      @RequestParam(required = false) Position position,
      @RequestParam(required = false) @Min(18) @Max(99) Integer minAge,
      @RequestParam(required = false) @Min(18) @Max(99) Integer maxAge,
      @RequestParam(required = false) MedicalStatus medicalStatus,
      @RequestParam(required = false) PreferredFoot preferredFoot) {
    PlayerFilter filter =
        new PlayerFilter(status, position, minAge, maxAge, medicalStatus, preferredFoot);
    return playerService.list(filter).stream().map(PlayerResponse::from).toList();
  }

  /** One player of the club, including a released one ({@code active: false}). */
  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  PlayerResponse get(@PathVariable String id) {
    return PlayerResponse.from(playerService.get(id));
  }

  /** Adds an active player to the club: 201, with the new player's URL in {@code Location}. */
  @PostMapping
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  ResponseEntity<PlayerResponse> create(@Valid @RequestBody CreatePlayerRequest request) {
    PlayerResponse created = PlayerResponse.from(playerService.create(request));
    URI location =
        ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id())
            .toUri();
    return ResponseEntity.created(location).body(created);
  }

  /**
   * Replaces all editable fields of an active player. The body carries the {@code version} the
   * client loaded; if the player has been saved since, the edit is refused with a 409.
   */
  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  PlayerResponse update(@PathVariable String id, @Valid @RequestBody UpdatePlayerRequest request) {
    return PlayerResponse.from(playerService.update(id, request));
  }

  /**
   * The player leaves the club ({@code active: false}); their jersey number stays on record but is
   * free for others. 409 if already released or if the player has been saved since {@code version}.
   */
  @PostMapping("/{id}/release")
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  PlayerResponse release(
      @PathVariable String id, @Valid @RequestBody ReleasePlayerRequest request) {
    return PlayerResponse.from(playerService.release(id, request));
  }

  /**
   * Brings a released player back with the {@code jerseyNumber} in the body ({@code null} or absent
   * for none). 409 if already active, if the number is taken, or if the player has been saved since
   * {@code version}.
   */
  @PostMapping("/{id}/reactivate")
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  PlayerResponse reactivate(
      @PathVariable String id, @Valid @RequestBody ReactivatePlayerRequest request) {
    return PlayerResponse.from(playerService.reactivate(id, request));
  }

  /**
   * Permanently deletes a player created by mistake, active or released: 204. Not version-checked
   * (see {@link PlayerService#delete}). Leaving the club is a release, not this.
   */
  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  ResponseEntity<Void> delete(@PathVariable String id) {
    playerService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
