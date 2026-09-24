package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.support.SimpleMongoRepository;
import org.springframework.data.repository.core.support.AbstractRepositoryMetadata;

/**
 * Proves {@link ClubScopedRepositoryFactory} picks the repository base class per entity — and that
 * an entity that is neither {@link ClubScopedEntity} nor {@link NotClubScoped} can't get a
 * repository at all, rather than silently getting an unscoped one.
 */
class ClubScopedRepositoryFactoryTest {

  static class UnmarkedEntity {}

  @NotClubScoped
  static class MarkedNotClubScopedEntity {}

  interface ClubScopedRepo extends MongoRepository<TestClubScopedEntity, String> {}

  interface NotClubScopedRepo extends MongoRepository<MarkedNotClubScopedEntity, String> {}

  interface UnmarkedRepo extends MongoRepository<UnmarkedEntity, String> {}

  private final ClubScopedRepositoryFactory factory =
      new ClubScopedRepositoryFactory(
          mock(MongoOperations.class, RETURNS_DEEP_STUBS), new ClubContext());

  @Test
  void clubScopedEntitiesGetTheClubScopedBaseClass() {
    assertThat(baseClassFor(ClubScopedRepo.class)).isEqualTo(ClubScopedRepositoryImpl.class);
  }

  @Test
  void notClubScopedEntitiesGetThePlainBaseClass() {
    assertThat(baseClassFor(NotClubScopedRepo.class)).isEqualTo(SimpleMongoRepository.class);
  }

  @Test
  void refusesAnEntityThatIsNeitherClubScopedNorMarkedNotClubScoped() {
    assertThatThrownBy(() -> baseClassFor(UnmarkedRepo.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("UnmarkedEntity")
        .hasMessageContaining("@NotClubScoped");
  }

  private Class<?> baseClassFor(Class<?> repositoryInterface) {
    return factory.getRepositoryBaseClass(
        AbstractRepositoryMetadata.getMetadata(repositoryInterface));
  }
}
