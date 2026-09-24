package com.squadpulse.common;

import org.springframework.data.mongodb.repository.MongoRepository;

/** Test-only repository; picked up by the app's global {@code @EnableMongoRepositories} scan. */
interface TestClubScopedEntityRepository extends MongoRepository<TestClubScopedEntity, String> {}
