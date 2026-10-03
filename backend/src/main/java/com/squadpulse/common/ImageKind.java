package com.squadpulse.common;

/**
 * What an image stored through {@link ImageStorage} is for. Together with the owner's id it names
 * one image slot (see {@link ImageOwner}); each kind has its own owners, so a player id and a club
 * id can never collide.
 */
public enum ImageKind {
  PLAYER_PHOTO
}
