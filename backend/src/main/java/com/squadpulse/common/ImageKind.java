package com.squadpulse.common;

/**
 * What an image stored through {@link ImageStorage} is for. Together with the owner's id it names
 * one image slot (see {@link ImageOwner}); each kind has its own owners, so a player id and a club
 * id can never collide.
 */
public enum ImageKind {
  /** A player's photo; the owner id is the player's id. */
  PLAYER_PHOTO,

  /** The club's logo; the owner id is the clubId itself, taken from {@link ClubContext}. */
  CLUB_LOGO
}
