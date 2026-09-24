package com.squadpulse.common;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;
import org.springframework.data.mongodb.repository.support.SimpleMongoRepository;
import org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery;
import org.springframework.data.support.PageableExecutionUtils;

/**
 * Base repository implementation that transparently merges a {@code clubId} filter — read from
 * {@link ClubContext} — into every query, so no repository method can accidentally return or mutate
 * another club's data.
 *
 * <p>Registered globally as the {@code repositoryBaseClass} for every Spring Data MongoDB
 * repository in this application (see {@link MongoRepositoryConfig}), so any repository extending
 * {@code MongoRepository<T, ID>} for a {@link ClubScopedEntity} gets this behavior automatically,
 * with no per-repository opt-in.
 *
 * <p>{@link Example}-based query methods are intentionally not club-scoped (composing a {@code
 * clubId} criterion into an arbitrary {@link Example} probe isn't a straightforward Spring Data
 * extension point) — they throw rather than silently returning unscoped data. Nothing in this
 * codebase uses them today.
 *
 * <p><b>This class does not, and cannot, protect custom/derived query methods.</b> A repository
 * interface that declares its own finder — e.g. {@code findByName(String name)} — gets it built by
 * Spring Data directly against {@link MongoOperations} via query derivation, which never goes
 * through this class at all. Every such method must include {@code ClubId} in its name (e.g. {@code
 * findByNameAndClubId(String name, String clubId)}) and the caller must supply the current {@link
 * ClubContext#requireClubId()} — unless it's a lookup by a globally unique value that is explicitly
 * annotated {@link GloballyScoped} (e.g. {@code UserRepository.findByEmail} for login). An ArchUnit
 * test enforces this at build time — see {@code ClubScopedRepositoryMethodNamingTest} — but there's
 * no runtime backstop, so review any new finder on a {@link ClubScopedEntity} repository with this
 * in mind.
 */
public class ClubScopedRepositoryImpl<T extends ClubScopedEntity, ID extends Serializable>
    extends SimpleMongoRepository<T, ID> {

  private static final String CLUB_ID_FIELD = "clubId";
  private static final String EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE =
      "Example-based queries are not club-scoped by ClubScopedRepositoryImpl — use a derived"
          + " query method instead.";

  private final MongoEntityInformation<T, ID> entityInformation;
  private final MongoOperations mongoOperations;
  private final ClubContext clubContext;

  public ClubScopedRepositoryImpl(
      MongoEntityInformation<T, ID> entityInformation,
      MongoOperations mongoOperations,
      ClubContext clubContext) {
    super(entityInformation, mongoOperations);
    this.entityInformation = entityInformation;
    this.mongoOperations = mongoOperations;
    this.clubContext = clubContext;
  }

  @Override
  public <S extends T> S save(S entity) {
    stampOrValidateClubId(entity);
    return super.save(entity);
  }

  @Override
  public <S extends T> List<S> saveAll(Iterable<S> entities) {
    entities.forEach(this::stampOrValidateClubId);
    return super.saveAll(entities);
  }

  @Override
  public <S extends T> S insert(S entity) {
    stampOrValidateClubId(entity);
    return super.insert(entity);
  }

  @Override
  public <S extends T> List<S> insert(Iterable<S> entities) {
    entities.forEach(this::stampOrValidateClubId);
    return super.insert(entities);
  }

  @Override
  public Optional<T> findById(ID id) {
    String clubId = clubContext.requireClubId();
    return Optional.ofNullable(
        mongoOperations.findOne(
            scopedIdQuery(id, clubId),
            entityInformation.getJavaType(),
            entityInformation.getCollectionName()));
  }

  @Override
  public boolean existsById(ID id) {
    String clubId = clubContext.requireClubId();
    return mongoOperations.exists(
        scopedIdQuery(id, clubId),
        entityInformation.getJavaType(),
        entityInformation.getCollectionName());
  }

  @Override
  public List<T> findAll() {
    String clubId = clubContext.requireClubId();
    return mongoOperations.find(
        scopedQuery(clubId),
        entityInformation.getJavaType(),
        entityInformation.getCollectionName());
  }

  @Override
  public List<T> findAll(Sort sort) {
    String clubId = clubContext.requireClubId();
    return mongoOperations.find(
        scopedQuery(clubId).with(sort),
        entityInformation.getJavaType(),
        entityInformation.getCollectionName());
  }

  @Override
  public Page<T> findAll(Pageable pageable) {
    String clubId = clubContext.requireClubId();
    Query query = scopedQuery(clubId).with(pageable);
    List<T> content =
        mongoOperations.find(
            query, entityInformation.getJavaType(), entityInformation.getCollectionName());
    return PageableExecutionUtils.getPage(
        content,
        pageable,
        () ->
            mongoOperations.count(
                scopedQuery(clubId),
                entityInformation.getJavaType(),
                entityInformation.getCollectionName()));
  }

  @Override
  public List<T> findAllById(Iterable<ID> ids) {
    String clubId = clubContext.requireClubId();
    List<ID> idList = toList(ids);
    Query query =
        new Query(
            Criteria.where(entityInformation.getIdAttribute())
                .in(idList)
                .and(CLUB_ID_FIELD)
                .is(clubId));
    return mongoOperations.find(
        query, entityInformation.getJavaType(), entityInformation.getCollectionName());
  }

  @Override
  public long count() {
    String clubId = clubContext.requireClubId();
    return mongoOperations.count(
        scopedQuery(clubId),
        entityInformation.getJavaType(),
        entityInformation.getCollectionName());
  }

  @Override
  public void deleteById(ID id) {
    String clubId = clubContext.requireClubId();
    mongoOperations.remove(
        scopedIdQuery(id, clubId),
        entityInformation.getJavaType(),
        entityInformation.getCollectionName());
  }

  @Override
  public void delete(T entity) {
    String clubId = clubContext.requireClubId();
    mongoOperations.remove(
        scopedIdQuery(entityInformation.getId(entity), clubId),
        entityInformation.getJavaType(),
        entityInformation.getCollectionName());
  }

  @Override
  public void deleteAllById(Iterable<? extends ID> ids) {
    String clubId = clubContext.requireClubId();
    List<ID> idList = toList(ids);
    Query query =
        new Query(
            Criteria.where(entityInformation.getIdAttribute())
                .in(idList)
                .and(CLUB_ID_FIELD)
                .is(clubId));
    mongoOperations.remove(
        query, entityInformation.getJavaType(), entityInformation.getCollectionName());
  }

  @Override
  public void deleteAll(Iterable<? extends T> entities) {
    List<ID> ids = new ArrayList<>();
    entities.forEach(entity -> ids.add(entityInformation.getId(entity)));
    deleteAllById(ids);
  }

  @Override
  public void deleteAll() {
    String clubId = clubContext.requireClubId();
    mongoOperations.remove(
        scopedQuery(clubId),
        entityInformation.getJavaType(),
        entityInformation.getCollectionName());
  }

  @Override
  public <S extends T> Optional<S> findOne(Example<S> example) {
    throw new UnsupportedOperationException(EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE);
  }

  @Override
  public <S extends T> List<S> findAll(Example<S> example) {
    throw new UnsupportedOperationException(EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE);
  }

  @Override
  public <S extends T> List<S> findAll(Example<S> example, Sort sort) {
    throw new UnsupportedOperationException(EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE);
  }

  @Override
  public <S extends T> Page<S> findAll(Example<S> example, Pageable pageable) {
    throw new UnsupportedOperationException(EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE);
  }

  @Override
  public <S extends T> long count(Example<S> example) {
    throw new UnsupportedOperationException(EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE);
  }

  @Override
  public <S extends T> boolean exists(Example<S> example) {
    throw new UnsupportedOperationException(EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE);
  }

  @Override
  public <S extends T, R> R findBy(
      Example<S> example, java.util.function.Function<FetchableFluentQuery<S>, R> queryFunction) {
    throw new UnsupportedOperationException(EXAMPLE_QUERIES_UNSUPPORTED_MESSAGE);
  }

  /**
   * Stamps or validates the {@code clubId} of an entity about to be saved.
   *
   * <p>{@code save()} is an upsert by id: if the entity's id already belongs to a document in
   * MongoDB, blindly trusting the in-memory entity's own {@code clubId} field would let a caller in
   * club A's context overwrite club B's document just by constructing an object with club B's id
   * (with {@code clubId} left null or forged to "club-A") — the in-memory field alone proves
   * nothing about who actually owns that id. So when the entity has an id, this looks up the
   * document currently stored for it — deliberately via {@link MongoOperations#findById}, not the
   * club-scoped query path, since the whole point is to see the true owner regardless of the
   * caller's context — and that stored clubId, not the incoming object's field, decides whether
   * this is a legitimate update.
   *
   * <p><b>Known limitation, accepted for now:</b> this is a look-up-then-act check, not an atomic
   * operation — the read here and the write in {@code super.save(entity)} are two separate
   * round-trips, so a concurrent request touching the same id could interleave between them. A
   * proper fix (e.g. a conditional/{@code findAndModify}-style write that enforces the clubId match
   * atomically at the database level) is real added complexity that isn't justified at this
   * project's current scale (no concurrent-write load yet). Revisit under the "Hardening &amp;
   * deployment" phase (docs/spec.md phase 6+) rather than assuming this is already handled.
   */
  private void stampOrValidateClubId(T entity) {
    String currentClubId = clubContext.requireClubId();
    ID id = entityInformation.getId(entity);
    T existing = id == null ? null : findExistingRegardlessOfClub(id);

    if (existing == null) {
      if (entity.getClubId() == null) {
        entity.setClubId(currentClubId);
      } else if (!entity.getClubId().equals(currentClubId)) {
        throw new CrossClubAccessException(
            "Attempted to insert a %s tagged for club '%s' while the current club context is '%s'"
                .formatted(
                    entityInformation.getJavaType().getSimpleName(),
                    entity.getClubId(),
                    currentClubId));
      }
      return;
    }

    if (!existing.getClubId().equals(currentClubId)) {
      throw new CrossClubAccessException(
          ("Attempted to save a %s with id '%s' that belongs to club '%s' while the current club"
                  + " context is '%s'")
              .formatted(
                  entityInformation.getJavaType().getSimpleName(),
                  id,
                  existing.getClubId(),
                  currentClubId));
    }
    // A legitimate update: force the entity's clubId to the true, already-verified owner,
    // regardless of whatever the caller happened to set (or leave null) on the in-memory object.
    entity.setClubId(currentClubId);
  }

  private T findExistingRegardlessOfClub(ID id) {
    return mongoOperations.findById(
        id, entityInformation.getJavaType(), entityInformation.getCollectionName());
  }

  private Query scopedQuery(String clubId) {
    return new Query(Criteria.where(CLUB_ID_FIELD).is(clubId));
  }

  private Query scopedIdQuery(ID id, String clubId) {
    return new Query(
        Criteria.where(entityInformation.getIdAttribute()).is(id).and(CLUB_ID_FIELD).is(clubId));
  }

  private static <E> List<E> toList(Iterable<? extends E> iterable) {
    List<E> list = new ArrayList<>();
    iterable.forEach(list::add);
    return list;
  }
}
