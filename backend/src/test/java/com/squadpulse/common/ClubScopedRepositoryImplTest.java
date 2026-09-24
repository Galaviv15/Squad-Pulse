package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.repository.query.MongoEntityInformation;

/**
 * Unit-level proof (mocked {@link MongoOperations}, no real database) that a missing {@link
 * ClubContext} fails loudly instead of silently falling through to an unscoped query. The
 * real-database behavior of the clubId filter itself is covered by {@link
 * ClubScopedRepositoryImplIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ClubScopedRepositoryImplTest {

  @Mock private MongoEntityInformation<TestClubScopedEntity, String> entityInformation;
  @Mock private MongoOperations mongoOperations;

  private ClubContext clubContext;
  private ClubScopedRepositoryImpl<TestClubScopedEntity, String> repository;

  @BeforeEach
  void setUp() {
    clubContext = new ClubContext();
    repository = new ClubScopedRepositoryImpl<>(entityInformation, mongoOperations, clubContext);
  }

  @AfterEach
  void tearDown() {
    clubContext.clear();
  }

  @Test
  void findAllThrowsAndNeverTouchesTheDatabaseWhenNoClubIdIsSet() {
    assertThatThrownBy(() -> repository.findAll()).isInstanceOf(MissingClubContextException.class);

    verifyNoInteractions(mongoOperations);
  }

  @Test
  void findByIdThrowsAndNeverTouchesTheDatabaseWhenNoClubIdIsSet() {
    assertThatThrownBy(() -> repository.findById("some-id"))
        .isInstanceOf(MissingClubContextException.class);

    verifyNoInteractions(mongoOperations);
  }

  @Test
  void countThrowsAndNeverTouchesTheDatabaseWhenNoClubIdIsSet() {
    assertThatThrownBy(() -> repository.count()).isInstanceOf(MissingClubContextException.class);

    verifyNoInteractions(mongoOperations);
  }

  @Test
  void saveThrowsWhenNoClubIdIsSetAndTheEntityHasNoneEither() {
    assertThatThrownBy(() -> repository.save(new TestClubScopedEntity("unstamped")))
        .isInstanceOf(MissingClubContextException.class);

    verifyNoInteractions(mongoOperations);
  }
}
