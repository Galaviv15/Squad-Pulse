package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.squadpulse.common.ClubContext;
import com.squadpulse.common.ImageKind;
import com.squadpulse.common.ImageOwner;
import com.squadpulse.common.ImageStorage;
import com.squadpulse.common.ImageType;
import com.squadpulse.common.MissingClubContextException;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.common.TestImages;
import com.squadpulse.common.ValidatedImage;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ClubLogoService} on a mocked storage: the owner is always the context's clubId, and the
 * club is checked before anything is stored.
 */
class ClubLogoServiceTest {

  private static final ImageOwner LOGO = new ImageOwner(ImageKind.CLUB_LOGO, "club-a");
  private static final ValidatedImage PNG = new ValidatedImage(TestImages.png(), ImageType.PNG);

  private final ClubRepository clubRepository = mock(ClubRepository.class);
  private final ImageStorage imageStorage = mock(ImageStorage.class);
  private final ClubContext clubContext = new ClubContext();
  private final ClubLogoService service =
      new ClubLogoService(clubRepository, clubContext, imageStorage);

  @AfterEach
  void clearContext() {
    clubContext.clear();
  }

  @Test
  void uploadStoresUnderTheContextsClubIdOnceTheClubIsFound() {
    clubContext.setClubId("club-a");
    when(clubRepository.findById("club-a")).thenReturn(Optional.of(new Club()));

    service.upload(PNG);

    verify(imageStorage).store(LOGO, PNG);
  }

  @Test
  void aMissingClubIsAServerErrorAndNothingIsStored() {
    clubContext.setClubId("club-a");
    when(clubRepository.findById("club-a")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.upload(PNG))
        .isExactlyInstanceOf(IllegalStateException.class)
        .hasMessageContaining("club-a");
    verify(imageStorage, never()).store(any(), any());
  }

  @Test
  void noLogoIs404WithoutIds() {
    clubContext.setClubId("club-a");
    when(imageStorage.find(LOGO)).thenReturn(Optional.empty());

    assertThatThrownBy(service::logo)
        .isInstanceOf(NotFoundException.class)
        .hasMessage("This club has no logo");
  }

  @Test
  void deleteAndHasLogoUseTheContextsClubId() {
    clubContext.setClubId("club-a");
    when(imageStorage.exists(LOGO)).thenReturn(true);

    service.delete();

    verify(imageStorage).delete(LOGO);
    assertThat(service.hasLogo()).isTrue();
  }

  @Test
  void withoutAClubContextNothingIsTouched() {
    assertThatThrownBy(() -> service.upload(PNG)).isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(service::logo).isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(service::delete).isInstanceOf(MissingClubContextException.class);
    verify(clubRepository, never()).findById(any());
  }
}
