package com.squadpulse.common;

/**
 * Base class for every tenant-scoped domain document (Player, User, ...).
 *
 * <p>{@link ClubScopedRepositoryImpl} reads and writes this field directly, so any entity that
 * needs club isolation must extend this class rather than adding its own {@code clubId} field.
 *
 * <p>Extending this class only protects the standard {@code MongoRepository} methods (findAll,
 * findById, save, delete, count, ...). Any custom/derived finder a repository interface declares
 * for this entity (e.g. {@code findByName(...)}) bypasses {@link ClubScopedRepositoryImpl} entirely
 * and must include {@code ClubId} in its method name — enforced at build time by an ArchUnit test,
 * not by anything at runtime. The only exception is a lookup by a globally unique value explicitly
 * marked {@link GloballyScoped} (e.g. {@code UserRepository.findByEmail} for login).
 */
public abstract class ClubScopedEntity {

  private String clubId;

  public String getClubId() {
    return clubId;
  }

  public void setClubId(String clubId) {
    this.clubId = clubId;
  }
}
