package com.squadpulse.common.archunitfixture;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

/**
 * Deliberately violates the club-scoped-method rule two ways: {@code findByName} is a derived query
 * with no {@code ClubId} in it, and {@code lookupSomehow} is an {@code @Query}-annotated method
 * with an arbitrary name that doesn't match any derived-query naming convention at all — proving
 * the rule catches unscoped methods structurally, not by matching name patterns. Used only by
 * {@code ClubScopedRepositoryMethodNamingRuleTest} to prove the ArchUnit rule actually catches
 * both.
 */
public interface RepositoryWithUnscopedFinder
    extends MongoRepository<FixtureClubScopedEntity, String> {

  List<FixtureClubScopedEntity> findByName(String name);

  @Query("{ 'name' : ?0 }")
  List<FixtureClubScopedEntity> lookupSomehow(String name);
}
