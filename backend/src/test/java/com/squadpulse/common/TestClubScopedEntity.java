package com.squadpulse.common;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Minimal tenant-scoped entity used only to exercise {@link ClubScopedRepositoryImpl}. */
@Document("test_club_scoped_entities")
class TestClubScopedEntity extends ClubScopedEntity {

  @Id private String id;
  private String name;

  TestClubScopedEntity() {}

  TestClubScopedEntity(String name) {
    this.name = name;
  }

  TestClubScopedEntity(String id, String name) {
    this.id = id;
    this.name = name;
  }

  String getId() {
    return id;
  }

  String getName() {
    return name;
  }

  void setName(String name) {
    this.name = name;
  }
}
