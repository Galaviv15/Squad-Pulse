package com.squadpulse.common;

import com.mongodb.MongoGridFSException;
import com.mongodb.client.gridfs.model.GridFSFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsOperations;
import org.springframework.data.mongodb.gridfs.GridFsResource;
import org.springframework.stereotype.Component;

/**
 * {@link ImageStorage} on MongoDB GridFS, in the dedicated {@value #BUCKET} bucket (collections
 * {@code images.files} and {@code images.chunks}). The only class in the codebase that touches
 * GridFS — an ArchUnit rule keeps GridFS types out of every other package.
 *
 * <p><b>Club isolation is applied here, by hand.</b> GridFS isn't a Spring Data repository, so
 * {@link ClubScopedRepositoryImpl} doesn't protect it, and nothing would catch a query missing its
 * clubId. So every file carries {@code metadata.clubId}, and every query in this class is built by
 * {@link #clubQuery}, which always adds {@code metadata.clubId = ClubContext.requireClubId()}.
 * Never build a {@link Query} here any other way. Deletes go by the {@code _id}s such a query
 * returned, and are themselves club-scoped too.
 *
 * <p><b>Files.</b> Metadata: {@code clubId}, {@code kind}, {@code ownerId}, and the detected {@code
 * type} ({@link ImageType} name, what reads use), plus its media type in GridFS's standard {@code
 * _contentType} for tooling. The filename is generated ({@code <kind>/<ownerId>}); the client's
 * filename is never stored.
 *
 * <p><b>Replacing without a transaction.</b> GridFS calls here don't join a Mongo transaction (the
 * bucket isn't given a session), so replacement is made race-safe by ordering instead. An owner's
 * files are totally ordered by {@code (uploadDate, _id)}; {@link #find} returns the latest. {@link
 * #store} uploads the new file first, then deletes only the owner's files strictly <i>before</i> it
 * in that order — never "everything but mine", or two concurrent stores would delete each other's
 * file and leave none. With two concurrent stores, the later one in the order removes the earlier
 * one, and the earlier one removes neither, so one remains. A stale file can survive briefly: an
 * earlier-dated file whose upload completes only after the later store's cleanup has run is removed
 * by nobody, as neither cleanup covers it then. It is never served (the later file wins) and the
 * owner's next store or delete removes it. {@code uploadDate} is set by the driver from the
 * application's clock when the upload completes, at millisecond resolution; {@code _id} breaks
 * ties. Clock skew between instances can only change which upload wins, not that exactly one does.
 */
@Component
public class GridFsImageStorage implements ImageStorage {

  /** The GridFS bucket for every image; its collections are {@code images.files/chunks}. */
  public static final String BUCKET = "images";

  static final String FILES_COLLECTION = BUCKET + ".files";

  /**
   * Finds an owner's files newest first, and a kind's owners in a club; created by {@link
   * ImageIndexInitializer}.
   */
  static final String OWNER_INDEX = "images_club_kind_owner_uploadDate";

  static final String CLUB_ID = "metadata.clubId";
  static final String KIND = "metadata.kind";
  static final String OWNER_ID = "metadata.ownerId";
  static final String UPLOAD_DATE = "uploadDate";
  private static final String ID = "_id";

  private static final Sort LATEST_FIRST =
      Sort.by(Sort.Order.desc(UPLOAD_DATE), Sort.Order.desc(ID));

  /**
   * How often {@link #find} looks again when the file it found is removed while being read (a
   * concurrent replace or delete).
   */
  private static final int READ_ATTEMPTS = 3;

  private final GridFsOperations gridFs;
  private final ClubContext clubContext;

  /**
   * @param gridFs bound to the {@value #BUCKET} bucket (see {@link ImageStorageConfig})
   */
  public GridFsImageStorage(GridFsOperations gridFs, ClubContext clubContext) {
    this.gridFs = gridFs;
    this.clubContext = clubContext;
  }

  @Override
  public void store(ImageOwner owner, ValidatedImage image) {
    Document metadata =
        new Document("clubId", clubContext.requireClubId())
            .append("kind", owner.kind().name())
            .append("ownerId", owner.ownerId())
            .append("type", image.type().name());
    ObjectId id =
        gridFs.store(
            new ByteArrayInputStream(image.content()),
            owner.kind().name() + "/" + owner.ownerId(),
            image.type().mediaType(),
            metadata);
    GridFSFile stored = gridFs.findOne(clubQuery(Criteria.where(ID).is(id)));
    if (stored == null) {
      // A concurrent, later store or a delete has already removed it — and everything before it.
      return;
    }
    Date uploaded = stored.getUploadDate();
    deleteFiles(
        new Criteria()
            .andOperator(
                ownerCriteria(owner),
                new Criteria()
                    .orOperator(
                        Criteria.where(UPLOAD_DATE).lt(uploaded),
                        new Criteria()
                            .andOperator(
                                Criteria.where(UPLOAD_DATE).is(uploaded),
                                Criteria.where(ID).lt(id)))));
  }

  /**
   * The content is read in full here (images are small), so a file removed while it's being read is
   * noticed now — and the owner's files looked up again — rather than halfway through a response.
   */
  @Override
  public Optional<StoredImage> find(ImageOwner owner) {
    for (int attempt = 1; ; attempt++) {
      GridFSFile file =
          gridFs.find(clubQuery(ownerCriteria(owner)).with(LATEST_FIRST).limit(1)).first();
      if (file == null) {
        return Optional.empty();
      }
      try {
        byte[] content = read(file);
        return Optional.of(
            new StoredImage(typeOf(file), content.length, new ByteArrayInputStream(content)));
      } catch (MongoGridFSException e) {
        if (attempt == READ_ATTEMPTS) {
          throw e;
        }
      }
    }
  }

  @Override
  public boolean exists(ImageOwner owner) {
    return gridFs.findOne(clubQuery(ownerCriteria(owner))) != null;
  }

  @Override
  public Set<String> ownerIdsWithImage(ImageKind kind) {
    Set<String> ownerIds = new HashSet<>();
    for (GridFSFile file : gridFs.find(clubQuery(Criteria.where(KIND).is(kind.name())))) {
      ownerIds.add(file.getMetadata().getString("ownerId"));
    }
    return ownerIds;
  }

  @Override
  public void delete(ImageOwner owner) {
    deleteFiles(ownerCriteria(owner));
  }

  /**
   * The only way a query is built in this class: {@code criteria}, restricted to the caller's club.
   *
   * @throws MissingClubContextException if there's no club context
   */
  private Query clubQuery(Criteria criteria) {
    return new Query(
        new Criteria()
            .andOperator(Criteria.where(CLUB_ID).is(clubContext.requireClubId()), criteria));
  }

  private static Criteria ownerCriteria(ImageOwner owner) {
    return Criteria.where(KIND).is(owner.kind().name()).and(OWNER_ID).is(owner.ownerId());
  }

  /**
   * Deletes the caller's club's files matching {@code criteria}, one by one. A file that's already
   * gone — a concurrent store or delete removed it first — is skipped: the driver deletes its
   * chunks and then reports the missing file, so there's nothing left behind.
   */
  private void deleteFiles(Criteria criteria) {
    List<ObjectId> ids = new ArrayList<>();
    gridFs.find(clubQuery(criteria)).forEach(file -> ids.add(file.getObjectId()));
    for (ObjectId id : ids) {
      try {
        gridFs.delete(clubQuery(Criteria.where(ID).is(id)));
      } catch (MongoGridFSException e) {
        // Removed concurrently: the outcome — no such file — is the one we wanted.
      }
    }
  }

  private byte[] read(GridFSFile file) {
    GridFsResource resource = gridFs.getResource(file);
    try (InputStream in = resource.getInputStream()) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read image " + file.getObjectId(), e);
    }
  }

  private static ImageType typeOf(GridFSFile file) {
    return ImageType.valueOf(file.getMetadata().getString("type"));
  }
}
