package com.squadpulse.common.archunitfixture;

import com.squadpulse.common.GloballyScoped;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Complies with the club-scoped-method rule via the escape hatch rather than the naming convention:
 * {@code findByCode} has no {@code ClubId} in its name but is annotated {@link GloballyScoped}.
 * Used only by {@code ClubScopedRepositoryMethodNamingRuleTest} to prove the annotation alone
 * satisfies the rule.
 */
public interface RepositoryWithGloballyScopedFinder
    extends MongoRepository<FixtureClubScopedEntity, String> {

  @GloballyScoped
  Optional<FixtureClubScopedEntity> findByCode(String code);
}
