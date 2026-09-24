package com.squadpulse.auth;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for {@link Club}. Not club-scoped — {@link Club} is {@link
 * com.squadpulse.common.NotClubScoped}, so this is built on plain {@code SimpleMongoRepository}.
 */
public interface ClubRepository extends MongoRepository<Club, String> {}
