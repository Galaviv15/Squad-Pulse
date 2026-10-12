package com.squadpulse.scrapingintegration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The "refresh data" button's backend (KAN-68): {@code POST /matches/refresh} ({@code EDIT_FULL})
 * starts a scrape of the caller's club's football.org.il data, {@code GET /matches/refresh} ({@code
 * VIEW_ONLY}) shows its status. Both take no parameters and no body: the club comes from the access
 * token (via {@code ClubContext}), the ids from the club's own mapping. All the logic is in {@link
 * ScrapeTriggerService}.
 *
 * <p>Every outcome is a state to show, not an error: {@code 202} for {@code STARTED}, {@code 200}
 * for the rest.
 */
@RestController
@RequestMapping("/matches/refresh")
class RefreshController {

  private final ScrapeTriggerService triggerService;

  RefreshController(ScrapeTriggerService triggerService) {
    this.triggerService = triggerService;
  }

  @PostMapping
  @PreAuthorize("hasAuthority('EDIT_FULL')")
  ResponseEntity<RefreshStatusResponse> refresh() {
    RefreshStatusResponse response = triggerService.refresh(TriggerSource.USER);
    return ResponseEntity.status(
            response.outcome() == RefreshStatusResponse.Outcome.STARTED
                ? HttpStatus.ACCEPTED
                : HttpStatus.OK)
        .body(response);
  }

  @GetMapping
  @PreAuthorize("hasAuthority('VIEW_ONLY')")
  RefreshStatusResponse status() {
    return triggerService.status();
  }
}
