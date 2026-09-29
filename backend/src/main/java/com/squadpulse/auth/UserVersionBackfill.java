package com.squadpulse.auth;

import com.mongodb.client.result.UpdateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Gives every {@link User} document stored before KAN-24 a {@code version} field of 0, once, at
 * startup.
 *
 * <p><b>Why it's needed.</b> {@link User#getVersion()} is a {@code @Version} property, and Spring
 * Data decides between insert and update by it: a user whose version is {@code null} counts as
 * <i>new</i>, so {@code save} would try to <b>insert</b> a document that already exists and fail
 * with a duplicate key — every password reset and permission change on a pre-KAN-24 user would
 * break. After the backfill, such a document loads with version 0 and saves as a normal versioned
 * update.
 *
 * <p><b>Why here.</b> It runs while the application context is being built ({@link
 * InitializingBean}), so it has finished before the embedded web server starts accepting requests —
 * Boot only starts the server's connector in its start/stop lifecycle, after every singleton
 * exists. An {@code ApplicationRunner} would run too late: after the server is already up. If it
 * fails, startup fails, rather than the app running with users it can't update.
 *
 * <p><b>Why it bypasses the club-scoped repository</b> (see CLAUDE.md standing rule 4). This is the
 * one direct {@link MongoOperations} write outside {@code common}, and it goes across all clubs on
 * purpose: it's a schema backfill, not a tenant operation. It filters on nothing but the absence of
 * {@code version}, sets nothing but {@code version}, and reads and returns no tenant data — only a
 * count. The query and update are sent unmapped (by collection name, not entity class), so no
 * auditing or version logic adds anything to them.
 *
 * <p>Idempotent: once every document has the field, it matches nothing. It can be deleted once
 * every environment has been backfilled — today that's only local development databases, since
 * there's no production database yet (Phase 6+).
 *
 * <p><b>Test-only switch:</b> {@value #ENABLED_PROPERTY}{@code =false} turns it off. It exists
 * solely so the infrastructure-free smoke test ({@code SquadpulseApplicationTests}) can start the
 * context without a database, and is deliberately not in application.yml or the README. <b>Never
 * disable it in a real environment:</b> against a database holding users without a version, every
 * save of such a user — password reset, permission change — fails with a {@code
 * DuplicateKeyException} on {@code _id} (proven by {@code
 * UserRepositoryIntegrationTest#withoutTheBackfillALegacyDocumentCantBeSaved}).
 */
@Component
@ConditionalOnProperty(
    name = UserVersionBackfill.ENABLED_PROPERTY,
    havingValue = "true",
    matchIfMissing = true)
class UserVersionBackfill implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(UserVersionBackfill.class);

  static final String ENABLED_PROPERTY = "squadpulse.user-version-backfill.enabled";

  static final String VERSION_FIELD = "version";

  private final MongoOperations mongoOperations;

  UserVersionBackfill(MongoOperations mongoOperations) {
    this.mongoOperations = mongoOperations;
  }

  @Override
  public void afterPropertiesSet() {
    backfill();
  }

  /** Returns the number of documents that were given a version. */
  long backfill() {
    UpdateResult result =
        mongoOperations.updateMulti(
            Query.query(Criteria.where(VERSION_FIELD).exists(false)),
            Update.update(VERSION_FIELD, 0L),
            mongoOperations.getCollectionName(User.class));
    long modified = result.getModifiedCount();
    if (modified > 0) {
      log.info("Backfilled a version field on {} existing user document(s) (KAN-24)", modified);
    }
    return modified;
  }
}
