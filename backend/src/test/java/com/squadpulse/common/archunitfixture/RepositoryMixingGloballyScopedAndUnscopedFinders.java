package com.squadpulse.common.archunitfixture;

import com.squadpulse.common.GloballyScoped;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Pairs a legitimately {@link GloballyScoped} method ({@code findByCode}) with a sibling that has
 * neither {@code ClubId} in its name nor the annotation ({@code findByName}). Used only by {@code
 * ClubScopedRepositoryMethodNamingRuleTest} to prove the escape hatch isn't a blanket exemption:
 * the unannotated sibling must still fail, even on a repository that uses the annotation elsewhere.
 */
public interface RepositoryMixingGloballyScopedAndUnscopedFinders
    extends MongoRepository<FixtureClubScopedEntity, String> {

  @GloballyScoped
  Optional<FixtureClubScopedEntity> findByCode(String code);

  List<FixtureClubScopedEntity> findByName(String name);
}
