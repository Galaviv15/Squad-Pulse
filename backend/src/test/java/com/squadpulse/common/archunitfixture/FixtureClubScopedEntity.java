package com.squadpulse.common.archunitfixture;

import com.squadpulse.common.ClubScopedEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Minimal club-scoped entity used only as a fixture for the ArchUnit rule tests. */
@Document("archunit_fixture_entities")
public class FixtureClubScopedEntity extends ClubScopedEntity {

  @Id private String id;
  private String name;
  private String code;
}
