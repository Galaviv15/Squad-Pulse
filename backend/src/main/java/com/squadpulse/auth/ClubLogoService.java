package com.squadpulse.auth;

import com.squadpulse.common.ClubContext;
import com.squadpulse.common.ImageKind;
import com.squadpulse.common.ImageOwner;
import com.squadpulse.common.ImageStorage;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.common.StoredImage;
import com.squadpulse.common.ValidatedImage;
import org.springframework.stereotype.Service;

/**
 * The caller's club's optional logo (KAN-30), kept in {@link ImageStorage} under {@link
 * ImageKind#CLUB_LOGO} with the clubId itself as the owner id. That clubId is only ever the one
 * {@link JwtAuthenticationFilter} took from the access token — there is no way to name another
 * club. The {@link Club} document is never modified: the logo isn't on it.
 *
 * <p><b>Simpler than player photos, deliberately.</b> Unlike {@code PlayerService.uploadPhoto},
 * there's no re-check after storing: that guards against the owner being permanently deleted
 * meanwhile, and clubs can't be deleted. And a club has no "released" state, so nothing here is a
 * 409.
 */
@Service
class ClubLogoService {

  private final ClubRepository clubRepository;
  private final ClubContext clubContext;
  private final ImageStorage imageStorage;

  ClubLogoService(
      ClubRepository clubRepository, ClubContext clubContext, ImageStorage imageStorage) {
    this.clubRepository = clubRepository;
    this.clubContext = clubContext;
    this.imageStorage = imageStorage;
  }

  /**
   * Stores {@code logo} as the club's logo, replacing any earlier one. The club is loaded first, so
   * no file is ever stored under a clubId that has no club.
   *
   * @throws IllegalStateException if the caller's club doesn't exist — a data-integrity bug, so a
   *     generic 500
   */
  void upload(ValidatedImage logo) {
    String clubId = clubContext.requireClubId();
    if (clubRepository.findById(clubId).isEmpty()) {
      throw new IllegalStateException("Club " + clubId + " not found");
    }
    imageStorage.store(logoOf(clubId), logo);
  }

  /**
   * The club's logo.
   *
   * @throws NotFoundException if the club has none
   */
  StoredImage logo() {
    return imageStorage
        .find(logoOf(clubContext.requireClubId()))
        .orElseThrow(() -> new NotFoundException("This club has no logo"));
  }

  /** Removes the club's logo; nothing to do if there's none. */
  void delete() {
    imageStorage.delete(logoOf(clubContext.requireClubId()));
  }

  /** Whether the caller's club has a logo, in one storage query. */
  boolean hasLogo() {
    return imageStorage.exists(logoOf(clubContext.requireClubId()));
  }

  private static ImageOwner logoOf(String clubId) {
    return new ImageOwner(ImageKind.CLUB_LOGO, clubId);
  }
}
