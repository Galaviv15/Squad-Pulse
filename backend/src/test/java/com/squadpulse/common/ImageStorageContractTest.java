package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link ImageStorage} contract, written against the interface only, so any implementation —
 * GridFS today, object storage (S3/R2) later — can prove it by extending this class: one current
 * image per owner, replace, idempotent delete, and club scoping. Implementation-specific checks
 * (indexes, how many files are left, deterministic races) belong in the subclass.
 */
abstract class ImageStorageContractTest {

  static final String CLUB_A = "club-a";
  static final String CLUB_B = "club-b";
  static final ImageOwner PHOTO_1 = new ImageOwner(ImageKind.PLAYER_PHOTO, "player-1");
  static final ImageOwner PHOTO_2 = new ImageOwner(ImageKind.PLAYER_PHOTO, "player-2");

  final ClubContext clubContext = new ClubContext();

  /** The implementation under test, wired to the shared {@link ClubContext} thread-local. */
  abstract ImageStorage storage();

  /** Removes every stored image, of every club. */
  abstract void removeAllImages();

  @AfterEach
  void cleanUp() {
    clubContext.clear();
    removeAllImages();
  }

  @Test
  void aStoredImageIsFoundWithItsTypeLengthAndContent() throws Exception {
    store(CLUB_A, PHOTO_1, TestImages.png());

    clubContext.setClubId(CLUB_A);
    StoredImage found = storage().find(PHOTO_1).orElseThrow();
    assertThat(found.type()).isEqualTo(ImageType.PNG);
    assertThat(found.length()).isEqualTo(TestImages.png().length);
    assertThat(readAll(found)).isEqualTo(TestImages.png());
    assertThat(storage().exists(PHOTO_1)).isTrue();
  }

  @Test
  void anOwnerWithoutAnImageHasNone() {
    clubContext.setClubId(CLUB_A);

    assertThat(storage().find(PHOTO_1)).isEmpty();
    assertThat(storage().exists(PHOTO_1)).isFalse();
    assertThat(storage().ownerIdsWithImage(ImageKind.PLAYER_PHOTO)).isEmpty();
  }

  @Test
  void storingAgainReplacesTheImage() throws Exception {
    store(CLUB_A, PHOTO_1, TestImages.png());
    store(CLUB_A, PHOTO_1, TestImages.webp());

    clubContext.setClubId(CLUB_A);
    StoredImage found = storage().find(PHOTO_1).orElseThrow();
    assertThat(found.type()).isEqualTo(ImageType.WEBP);
    assertThat(readAll(found)).isEqualTo(TestImages.webp());
  }

  @Test
  void deleteRemovesTheImageAndIsIdempotent() {
    store(CLUB_A, PHOTO_1, TestImages.png());

    clubContext.setClubId(CLUB_A);
    storage().delete(PHOTO_1);
    assertThat(storage().find(PHOTO_1)).isEmpty();
    assertThat(storage().exists(PHOTO_1)).isFalse();

    storage().delete(PHOTO_1);
    storage().delete(PHOTO_2);
  }

  @Test
  void ownersAreIndependent() {
    store(CLUB_A, PHOTO_1, TestImages.png());
    store(CLUB_A, PHOTO_2, TestImages.jpeg());

    clubContext.setClubId(CLUB_A);
    assertThat(storage().ownerIdsWithImage(ImageKind.PLAYER_PHOTO))
        .containsExactlyInAnyOrder("player-1", "player-2");

    storage().delete(PHOTO_1);

    assertThat(storage().find(PHOTO_2).orElseThrow().type()).isEqualTo(ImageType.JPEG);
    assertThat(storage().ownerIdsWithImage(ImageKind.PLAYER_PHOTO)).containsExactly("player-2");
  }

  /** Another club's image doesn't exist for the caller: not found, not listed, not removable. */
  @Test
  void anotherClubsImageIsInvisibleAndUntouchable() throws Exception {
    store(CLUB_A, PHOTO_1, TestImages.png());

    clubContext.setClubId(CLUB_B);
    assertThat(storage().find(PHOTO_1)).isEmpty();
    assertThat(storage().exists(PHOTO_1)).isFalse();
    assertThat(storage().ownerIdsWithImage(ImageKind.PLAYER_PHOTO)).isEmpty();
    storage().delete(PHOTO_1);

    clubContext.setClubId(CLUB_A);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isEqualTo(TestImages.png());
  }

  /** The same owner id in two clubs is two separate slots: neither replaces the other. */
  @Test
  void theSameOwnerIdInAnotherClubIsASeparateImage() throws Exception {
    store(CLUB_A, PHOTO_1, TestImages.png());
    store(CLUB_B, PHOTO_1, TestImages.jpeg());

    clubContext.setClubId(CLUB_A);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isEqualTo(TestImages.png());
    storage().delete(PHOTO_1);

    clubContext.setClubId(CLUB_B);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isEqualTo(TestImages.jpeg());
    assertThat(storage().ownerIdsWithImage(ImageKind.PLAYER_PHOTO)).containsExactly("player-1");
  }

  @Test
  void withoutAClubContextEveryOperationFails() {
    ValidatedImage image = new ValidatedImage(TestImages.png(), ImageType.PNG);

    assertThatThrownBy(() -> storage().store(PHOTO_1, image))
        .isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage().find(PHOTO_1))
        .isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage().exists(PHOTO_1))
        .isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage().ownerIdsWithImage(ImageKind.PLAYER_PHOTO))
        .isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage().delete(PHOTO_1))
        .isInstanceOf(MissingClubContextException.class);
  }

  /** Two stores released together: both succeed, and the current image is one of the two. */
  @Test
  void concurrentStoresBothSucceedAndOneOfThemIsCurrent() throws Exception {
    byte[] first = TestImages.png();
    byte[] second = TestImages.otherPng();
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> stores =
          List.of(first, second).stream()
              .<Future<?>>map(
                  content ->
                      executor.submit(
                          () -> {
                            start.await(30, TimeUnit.SECONDS);
                            store(CLUB_A, PHOTO_1, content);
                            return null;
                          }))
              .toList();
      for (Future<?> store : stores) {
        store.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    clubContext.setClubId(CLUB_A);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isIn(first, second);
  }

  /** Stores {@code content} for {@code owner} as {@code clubId}, on the calling thread. */
  void store(String clubId, ImageOwner owner, byte[] content) {
    Optional<String> previous = clubContext.getClubId();
    clubContext.setClubId(clubId);
    try {
      storage().store(owner, new ValidatedImage(content, ImageType.detect(content).orElseThrow()));
    } finally {
      previous.ifPresentOrElse(clubContext::setClubId, clubContext::clear);
    }
  }

  static byte[] readAll(StoredImage image) throws Exception {
    try (InputStream in = image.content()) {
      return in.readAllBytes();
    }
  }
}
