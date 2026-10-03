package com.squadpulse.common.archunitfixture;

import com.squadpulse.common.ImageKind;
import com.squadpulse.common.ImageOwner;
import com.squadpulse.common.ImageStorage;

/**
 * Complies with the GridFS confinement rule: outside {@code common}, it reaches images only through
 * {@link ImageStorage}. Used only by {@code GridFsConfinementRuleTest}. Deliberately not a Spring
 * bean.
 */
public class ClassUsingImageStorage {

  private final ImageStorage imageStorage;

  public ClassUsingImageStorage(ImageStorage imageStorage) {
    this.imageStorage = imageStorage;
  }

  public boolean hasPhoto(String playerId) {
    return imageStorage.exists(new ImageOwner(ImageKind.PLAYER_PHOTO, playerId));
  }
}
