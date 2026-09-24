package com.squadpulse.common.archunitfixture;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Complies with the club-scoped-method rule: both {@code findByNameAndClubId} (a derived query) and
 * {@code lookupSomehowWithClubId} (an {@code @Query}-annotated method with an arbitrary name)
 * include {@code ClubId}. Used only by {@code ClubScopedRepositoryMethodNamingRuleTest} to prove
 * the ArchUnit rule doesn't flag either kind of correctly scoped method.
 */
public interface RepositoryWithScopedFinder
    extends MongoRepository<FixtureClubScopedEntity, String> {

  List<FixtureClubScopedEntity> findByNameAndClubId(String name, String clubId);

  @Query("{ 'name' : ?0, 'clubId' : ?1 }")
  List<FixtureClubScopedEntity> lookupSomehowWithClubId(String name, String clubId);
}
