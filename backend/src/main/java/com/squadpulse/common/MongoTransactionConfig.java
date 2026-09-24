package com.squadpulse.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Enables multi-document MongoDB transactions (e.g. creating a Club and its first Club Manager
 * together — both or neither). Spring Boot doesn't auto-configure a Mongo transaction manager.
 *
 * <p>MongoDB only supports transactions on a replica set, never on a standalone server — which is
 * why the local docker-compose MongoDB runs as a single-node replica set, and why the
 * Testcontainers-backed tests use {@code withReplicaSet()}.
 */
@Configuration
public class MongoTransactionConfig {

  @Bean
  MongoTransactionManager transactionManager(MongoDatabaseFactory databaseFactory) {
    return new MongoTransactionManager(databaseFactory);
  }
}
