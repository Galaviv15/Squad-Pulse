package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mongodb.MongoGridFSException;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.gridfs.GridFSFindIterable;
import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.InputStream;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;

/**
 * {@link GridFsImageStorage}'s club scoping, checked on every query it builds — the property the
 * repository layer can't give it — and its handling of a file removed concurrently. Behaviour on a
 * real MongoDB is in {@link GridFsImageStorageIntegrationTest}.
 */
class GridFsImageStorageTest {

  private static final ImageOwner OWNER = new ImageOwner(ImageKind.PLAYER_PHOTO, "p-1");

  private final GridFsOperations gridFs = mock(GridFsOperations.class);
  private final ClubContext clubContext = new ClubContext();
  private final GridFsImageStorage storage = new GridFsImageStorage(gridFs, clubContext);

  @AfterEach
  void clearClub() {
    clubContext.clear();
  }

  /** Every query of every operation, including the per-file deletes, carries the caller's club. */
  @Test
  void everyQueryIsRestrictedToTheCallersClub() {
    clubContext.setClubId("club-a");
    GridFSFile file = file(new ObjectId());
    GridFSFindIterable files = iterableOf(file);
    when(gridFs.find(any())).thenReturn(files);
    when(gridFs.findOne(any())).thenReturn(file);
    when(gridFs.store(any(InputStream.class), anyString(), anyString(), any(Document.class)))
        .thenReturn(file.getObjectId());

    storage.store(OWNER, new ValidatedImage(TestImages.png(), ImageType.PNG));
    storage.exists(OWNER);
    storage.ownerIdsWithImage(ImageKind.PLAYER_PHOTO);
    storage.delete(OWNER);

    ArgumentCaptor<Query> queries = ArgumentCaptor.forClass(Query.class);
    verify(gridFs, atLeastOnce()).find(queries.capture());
    verify(gridFs, atLeastOnce()).findOne(queries.capture());
    verify(gridFs, atLeastOnce()).delete(queries.capture());
    assertThat(queries.getAllValues())
        .isNotEmpty()
        .allSatisfy(
            query ->
                assertThat(query.getQueryObject().getList("$and", Document.class))
                    .contains(new Document("metadata.clubId", "club-a")));
  }

  @Test
  void theStoredFileCarriesTheClubKindOwnerAndTypeButNoClientFilename() {
    clubContext.setClubId("club-a");
    when(gridFs.store(any(InputStream.class), anyString(), anyString(), any(Document.class)))
        .thenReturn(new ObjectId());

    storage.store(OWNER, new ValidatedImage(TestImages.png(), ImageType.PNG));

    ArgumentCaptor<Document> metadata = ArgumentCaptor.forClass(Document.class);
    verify(gridFs)
        .store(any(InputStream.class), any(String.class), any(String.class), metadata.capture());
    verify(gridFs)
        .store(
            any(InputStream.class), eq("PLAYER_PHOTO/p-1"), eq("image/png"), any(Document.class));
    assertThat(metadata.getValue())
        .isEqualTo(
            new Document("clubId", "club-a")
                .append("kind", "PLAYER_PHOTO")
                .append("ownerId", "p-1")
                .append("type", "PNG"));
  }

  /** No club context fails before GridFS is touched — nothing is stored or read unscoped. */
  @Test
  void withoutAClubContextEveryOperationFailsBeforeTouchingGridFs() {
    ValidatedImage image = new ValidatedImage(TestImages.png(), ImageType.PNG);

    assertThatThrownBy(() -> storage.store(OWNER, image))
        .isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage.find(OWNER)).isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage.exists(OWNER)).isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage.ownerIdsWithImage(ImageKind.PLAYER_PHOTO))
        .isInstanceOf(MissingClubContextException.class);
    assertThatThrownBy(() -> storage.delete(OWNER)).isInstanceOf(MissingClubContextException.class);
    verifyNoInteractions(gridFs);
  }

  /**
   * The driver deletes the chunks, then reports a file that's no longer there — what a concurrent
   * delete looks like. The outcome is the one wanted, so it's not an error.
   */
  @Test
  void aFileRemovedConcurrentlyIsSkippedOnDelete() {
    clubContext.setClubId("club-a");
    GridFSFindIterable files = iterableOf(file(new ObjectId()), file(new ObjectId()));
    when(gridFs.find(any())).thenReturn(files);
    doThrow(new MongoGridFSException("No file found with the ObjectId")).when(gridFs).delete(any());

    assertThatCode(() -> storage.delete(OWNER)).doesNotThrowAnyException();
    verify(gridFs, times(2)).delete(any());
  }

  private static GridFSFile file(ObjectId id) {
    return new GridFSFile(
        new BsonObjectId(id),
        "PLAYER_PHOTO/p-1",
        64,
        255 * 1024,
        new Date(),
        new Document("clubId", "club-a")
            .append("kind", "PLAYER_PHOTO")
            .append("ownerId", "p-1")
            .append("type", "PNG"));
  }

  @SuppressWarnings("unchecked")
  private static GridFSFindIterable iterableOf(GridFSFile... files) {
    GridFSFindIterable iterable = mock(GridFSFindIterable.class);
    when(iterable.first()).thenReturn(files.length == 0 ? null : files[0]);
    when(iterable.sort(any())).thenReturn(iterable);
    when(iterable.limit(anyInt())).thenReturn(iterable);
    doAnswer(
            invocation -> {
              List.of(files).forEach(invocation.<Consumer<GridFSFile>>getArgument(0));
              return null;
            })
        .when(iterable)
        .forEach(any());
    when(iterable.iterator()).thenAnswer(invocation -> mongoCursor(files));
    return iterable;
  }

  private static MongoCursor<GridFSFile> mongoCursor(GridFSFile... files) {
    Iterator<GridFSFile> it = List.of(files).iterator();
    @SuppressWarnings("unchecked")
    MongoCursor<GridFSFile> cursor = mock(MongoCursor.class);
    when(cursor.hasNext()).thenAnswer(invocation -> it.hasNext());
    when(cursor.next()).thenAnswer(invocation -> it.next());
    return cursor;
  }
}
