package com.squadpulse.auth;

import org.springframework.stereotype.Service;

/**
 * The caller's club's settings (KAN-38) — today only its name. The club is always the one {@link
 * JwtAuthenticationFilter} took from the access token ({@link AuthenticatedUser#clubId()}); there
 * is no way to name another club.
 *
 * <p><b>Renaming is a targeted single-field update, without a version.</b> It's an absolute change
 * of one field, so two admins renaming at once simply means the later write wins, and each gets a
 * {@code 200} with what was stored right after its own write. The write is a {@code $set} of {@code
 * name} alone ({@link ClubRepository#updateNameByClubId}), never a load-modify-save of the whole
 * document, which would silently overwrite any other field changed concurrently. <b>When the club
 * gets several settings edited as one form, it must move to {@code @Version}</b> — with a backfill
 * for the existing club documents first, as {@link UserVersionBackfill} did for users (KAN-24) —
 * and to client-sent versions, like {@code squad.Player}.
 *
 * <p><b>Caller re-check on the write.</b> {@link #update} ({@code PATCH /clubs/me}) re-reads its
 * caller first ({@link ActiveCallerCheck}), like the user-management writes, so a deactivated
 * {@code ADMIN}'s still-valid access token can't rename the club. A future write to the club's
 * settings must do the same. {@link #club} doesn't: like every other read, it keeps working until
 * the token expires. Neither do the logo writes ({@link ClubLogoService}), deliberately: cosmetic,
 * reversible, and a deactivated user's token lives at most 15 minutes.
 *
 * <p>A missing club is an {@link IllegalStateException} (a generic 500), as on {@code /me}: a
 * token's club must exist.
 */
@Service
class ClubSettingsService {

  private final ActiveCallerCheck activeCallerCheck;
  private final ClubRepository clubRepository;
  private final ClubLogoService clubLogoService;

  ClubSettingsService(
      ActiveCallerCheck activeCallerCheck,
      ClubRepository clubRepository,
      ClubLogoService clubLogoService) {
    this.activeCallerCheck = activeCallerCheck;
    this.clubRepository = clubRepository;
    this.clubLogoService = clubLogoService;
  }

  /**
   * The caller's club.
   *
   * @throws IllegalStateException if the caller's club doesn't exist
   */
  ClubResponse club(AuthenticatedUser caller) {
    return response(caller);
  }

  /**
   * Applies {@code request}'s settings to the caller's club and returns the club as stored right
   * after the write — re-read, never built from the request.
   *
   * @throws CurrentUserUnavailableException if the caller is deactivated or no longer exists —
   *     nothing is written
   * @throws IllegalStateException if the caller's club doesn't exist. The update can't report that
   *     itself (see {@link ClubRepository#updateNameByClubId}), so the re-read does; a missing club
   *     matched nothing, so nothing was written either
   */
  ClubResponse update(AuthenticatedUser caller, UpdateClubRequest request) {
    activeCallerCheck.requireActive(caller);
    clubRepository.updateNameByClubId(caller.clubId(), request.name());
    return response(caller);
  }

  private ClubResponse response(AuthenticatedUser caller) {
    Club club =
        clubRepository
            .findById(caller.clubId())
            .orElseThrow(() -> new IllegalStateException("Club " + caller.clubId() + " not found"));
    return ClubResponse.from(club, clubLogoService.hasLogo());
  }
}
