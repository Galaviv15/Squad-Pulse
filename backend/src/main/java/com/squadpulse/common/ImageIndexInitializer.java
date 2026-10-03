package com.squadpulse.common;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

/**
 * Creates {@link GridFsImageStorage}'s index on {@code images.files} at startup. {@code
 * spring.data.mongodb.auto-index-creation} only covers mapped entities, and GridFS files aren't
 * one, so it's created here explicitly — under the same switch, so it's on wherever the entity
 * indexes are (application.yml) and off where they are (the infrastructure-free smoke test, which
 * has no database to create it in). Idempotent; the driver adds GridFS's own indexes on the first
 * upload.
 *
 * <p>Runs while the context is built, like Spring Data's own index creation, so the index exists
 * before the first request.
 */
@Component
@ConditionalOnProperty(name = "spring.data.mongodb.auto-index-creation", havingValue = "true")
class ImageIndexInitializer implements InitializingBean {

  private final MongoOperations mongoOperations;

  ImageIndexInitializer(MongoOperations mongoOperations) {
    this.mongoOperations = mongoOperations;
  }

  @Override
  public void afterPropertiesSet() {
    mongoOperations
        .indexOps(GridFsImageStorage.FILES_COLLECTION)
        .createIndex(
            new Index()
                .on(GridFsImageStorage.CLUB_ID, Sort.Direction.ASC)
                .on(GridFsImageStorage.KIND, Sort.Direction.ASC)
                .on(GridFsImageStorage.OWNER_ID, Sort.Direction.ASC)
                .on(GridFsImageStorage.UPLOAD_DATE, Sort.Direction.DESC)
                .named(GridFsImageStorage.OWNER_INDEX));
  }
}
