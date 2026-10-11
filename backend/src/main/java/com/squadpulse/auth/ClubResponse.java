package com.squadpulse.auth;

/**
 * The caller's club, as {@code GET} / {@code PATCH /clubs/me} return it and as {@code GET
 * /auth/users/me} embeds it in {@code club} — one record, so the two can't drift. Never {@code
 * createdAt}, the football.org.il mapping ({@link Club#getFootballOrgIl()}, the system owner's
 * setting) or anything else stored on {@link Club}. {@code hasLogo} says whether {@code GET
 * /clubs/me/logo} has an image, so the client can skip that request when it hasn't.
 */
record ClubResponse(String id, String name, boolean hasLogo) {

  static ClubResponse from(Club club, boolean hasLogo) {
    return new ClubResponse(club.getId(), club.getName(), hasLogo);
  }
}
