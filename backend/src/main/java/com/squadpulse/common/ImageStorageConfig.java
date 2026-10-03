package com.squadpulse.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;

/**
 * The application's one {@link GridFsTemplate}, bound to the {@value GridFsImageStorage#BUCKET}
 * bucket in code rather than through {@code spring.data.mongodb.gridfs.bucket}: the bucket's
 * collection names are baked into {@link GridFsImageStorage}'s index, so they shouldn't be
 * changeable by configuration alone. Defining it makes Spring Boot's own (default {@code fs}
 * bucket) back off, so that property has no effect.
 *
 * <p>Used only by {@link GridFsImageStorage}; nothing outside {@code common} may depend on GridFS
 * (ArchUnit-enforced).
 */
@Configuration
class ImageStorageConfig {

  @Bean
  GridFsTemplate gridFsTemplate(MongoDatabaseFactory databaseFactory, MongoTemplate mongoTemplate) {
    return new GridFsTemplate(
        databaseFactory, mongoTemplate.getConverter(), GridFsImageStorage.BUCKET);
  }
}
