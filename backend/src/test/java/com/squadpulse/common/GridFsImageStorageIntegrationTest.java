package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

/**
 * {@link GridFsImageStorage} on a real MongoDB: the {@link ImageStorageContractTest contract}, plus
 * what's GridFS-specific — the {@code images} bucket and its index, the files left behind after a
 * replace, the {@code (uploadDate, _id)} order, and deterministic races (a spied {@link
 * GridFsTemplate} holds each store between its upload and its cleanup).
 */
@SpringBootTest(
    properties = {
      "squadpulse.security.jwt-secret=test-only-jwt-secret-not-a-real-secret",
      "squadpulse.security.password-pepper=test-only-pepper-not-a-real-secret"
    })
@Testcontainers
class GridFsImageStorageIntegrationTest extends ImageStorageContractTest {

  @Container
  static final MongoDBContainer MONGO_DB_CONTAINER =
      new MongoDBContainer("mongo:7").withReplicaSet();

  @DynamicPropertySource
  static void mongoProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.mongodb.uri", MONGO_DB_CONTAINER::getReplicaSetUrl);
  }

  @Autowired private ImageStorage imageStorage;
  @Autowired private GridFsTemplate gridFsTemplate;
  @Autowired private MongoTemplate mongoTemplate;

  @Override
  ImageStorage storage() {
    return imageStorage;
  }

  @Override
  void removeAllImages() {
    mongoTemplate.remove(new Query(), "images.files");
    mongoTemplate.remove(new Query(), "images.chunks");
  }

  @Test
  void theOnlyImageStorageIsTheGridFsOneOnTheImagesBucket() {
    assertThat(imageStorage).isInstanceOf(GridFsImageStorage.class);
    store(CLUB_A, PHOTO_1, TestImages.png());

    assertThat(mongoTemplate.count(new Query(), "images.files")).isEqualTo(1);
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isEqualTo(1);
    assertThat(mongoTemplate.collectionExists("fs.files")).isFalse();
  }

  /** Created at startup, before anything is stored. */
  @Test
  void theOwnerIndexExistsAfterStartup() {
    IndexInfo index =
        mongoTemplate.indexOps("images.files").getIndexInfo().stream()
            .filter(info -> info.getName().equals(GridFsImageStorage.OWNER_INDEX))
            .findFirst()
            .orElseThrow();

    assertThat(index.getIndexFields())
        .extracting(field -> field.getKey() + ":" + field.getDirection())
        .containsExactly(
            "metadata.clubId:ASC", "metadata.kind:ASC", "metadata.ownerId:ASC", "uploadDate:DESC");
  }

  @Test
  void aFileCarriesItsClubKindOwnerAndTypeUnderAGeneratedName() {
    store(CLUB_A, PHOTO_1, TestImages.png());

    Document file = mongoTemplate.findOne(new Query(), Document.class, "images.files");
    assertThat(file.getString("filename")).isEqualTo("PLAYER_PHOTO/player-1");
    assertThat(file.get("metadata", Document.class))
        .containsEntry("clubId", CLUB_A)
        .containsEntry("kind", "PLAYER_PHOTO")
        .containsEntry("ownerId", "player-1")
        .containsEntry("type", "PNG")
        .containsEntry("_contentType", "image/png");
  }

  @Test
  void aReplaceLeavesExactlyOneFile() {
    store(CLUB_A, PHOTO_1, TestImages.png());
    store(CLUB_A, PHOTO_1, TestImages.jpeg());
    store(CLUB_A, PHOTO_1, TestImages.webp());

    assertThat(ownerFiles(CLUB_A, PHOTO_1)).hasSize(1);
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isEqualTo(1);
  }

  @Test
  void deleteLeavesNoFilesOrChunks() {
    store(CLUB_A, PHOTO_1, TestImages.png());

    clubContext.setClubId(CLUB_A);
    storage().delete(PHOTO_1);

    assertThat(mongoTemplate.count(new Query(), "images.files")).isZero();
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isZero();
  }

  /**
   * Equal upload dates are ordered by {@code _id}: the higher one is current, and a store removes
   * both, being after them.
   */
  @Test
  void equalUploadDatesAreOrderedById() throws Exception {
    ObjectId lower = rawFile(CLUB_A, PHOTO_1, TestImages.jpeg());
    ObjectId higher = rawFile(CLUB_A, PHOTO_1, TestImages.png());
    assertThat(higher).isGreaterThan(lower);
    mongoTemplate.updateMulti(
        new Query(), Update.update("uploadDate", new Date(1_000_000)), "images.files");

    clubContext.setClubId(CLUB_A);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isEqualTo(TestImages.png());

    store(CLUB_A, PHOTO_1, TestImages.webp());
    assertThat(ownerFiles(CLUB_A, PHOTO_1)).hasSize(1);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isEqualTo(TestImages.webp());
  }

  /**
   * A file dated after the new one — another instance's clock running ahead — is not removed and
   * stays current: a store only ever removes what comes before it. The next delete removes it.
   */
  @Test
  void aStoreNeverRemovesALaterFile() throws Exception {
    rawFile(CLUB_A, PHOTO_1, TestImages.jpeg());
    mongoTemplate.updateMulti(
        new Query(),
        Update.update("uploadDate", new Date(System.currentTimeMillis() + 3_600_000)),
        "images.files");

    store(CLUB_A, PHOTO_1, TestImages.png());

    clubContext.setClubId(CLUB_A);
    assertThat(ownerFiles(CLUB_A, PHOTO_1)).hasSize(2);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isEqualTo(TestImages.jpeg());
    storage().delete(PHOTO_1);
    assertThat(ownerFiles(CLUB_A, PHOTO_1)).isEmpty();
  }

  /**
   * Both uploads land before either cleans up — the worst case for "delete the others". With an
   * older photo already there, both try to remove it too. Exactly one file remains, one of the two.
   */
  @Test
  void twoStoresUploadingTogetherLeaveExactlyOneOfTheirFiles() throws Exception {
    store(CLUB_A, PHOTO_1, TestImages.webp());
    CyclicBarrier afterUpload = new CyclicBarrier(2);
    GridFsImageStorage storage =
        storageHeldAfterUpload(() -> afterUpload.await(30, TimeUnit.SECONDS));
    byte[] first = TestImages.png();
    byte[] second = TestImages.otherPng();

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> stores =
          List.of(first, second).stream()
              .<Future<?>>map(
                  content ->
                      executor.submit(
                          () -> {
                            clubContext.setClubId(CLUB_A);
                            try {
                              storage.store(PHOTO_1, new ValidatedImage(content, ImageType.PNG));
                            } finally {
                              clubContext.clear();
                            }
                            return null;
                          }))
              .toList();
      for (Future<?> store : stores) {
        store.get(60, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }

    List<Document> remaining = ownerFiles(CLUB_A, PHOTO_1);
    assertThat(remaining).hasSize(1);
    clubContext.setClubId(CLUB_A);
    assertThat(readAll(storage().find(PHOTO_1).orElseThrow())).isIn(first, second);
    assertThat(mongoTemplate.count(new Query(), "images.chunks")).isEqualTo(1);
  }

  /** A delete that runs between a store's upload and its cleanup: nothing left, no error. */
  @Test
  void aDeleteBetweenUploadAndCleanupLeavesNothing() {
    GridFsImageStorage storage = storageHeldAfterUpload(() -> imageStorage.delete(PHOTO_1));

    clubContext.setClubId(CLUB_A);
    storage.store(PHOTO_1, new ValidatedImage(TestImages.png(), ImageType.PNG));

    assertThat(ownerFiles(CLUB_A, PHOTO_1)).isEmpty();
    assertThat(storage().exists(PHOTO_1)).isFalse();
  }

  /** A storage whose every upload runs {@code afterUpload} before the store goes on. */
  private GridFsImageStorage storageHeldAfterUpload(ThrowingRunnable afterUpload) {
    GridFsTemplate held = spy(gridFsTemplate);
    doAnswer(
            invocation -> {
              Object id = invocation.callRealMethod();
              afterUpload.run();
              return id;
            })
        .when(held)
        .store(any(InputStream.class), anyString(), anyString(), any(Document.class));
    return new GridFsImageStorage(held, clubContext);
  }

  /** A GridFS file for {@code owner} written directly, bypassing {@link GridFsImageStorage}. */
  private ObjectId rawFile(String clubId, ImageOwner owner, byte[] content) {
    ImageType type = ImageType.detect(content).orElseThrow();
    return gridFsTemplate.store(
        new ByteArrayInputStream(content),
        owner.kind() + "/" + owner.ownerId(),
        type.mediaType(),
        new Document("clubId", clubId)
            .append("kind", owner.kind().name())
            .append("ownerId", owner.ownerId())
            .append("type", type.name()));
  }

  private List<Document> ownerFiles(String clubId, ImageOwner owner) {
    return mongoTemplate.find(
        Query.query(
            Criteria.where("metadata.clubId")
                .is(clubId)
                .and("metadata.kind")
                .is(owner.kind().name())
                .and("metadata.ownerId")
                .is(owner.ownerId())),
        Document.class,
        "images.files");
  }

  @FunctionalInterface
  private interface ThrowingRunnable {
    void run() throws Exception;
  }
}
