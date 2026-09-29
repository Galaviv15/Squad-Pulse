package com.squadpulse.squad;

import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for {@link Player}. Like every repository for a {@link
 * com.squadpulse.common.ClubScopedEntity}, the standard CRUD methods go through {@link
 * com.squadpulse.common.ClubScopedRepositoryImpl} and are automatically club-scoped: a player is
 * only ever visible to, and writable by, its own club.
 *
 * <p>Any finder added here must include {@code ClubId} in its name (ArchUnit-enforced). Players
 * have no globally unique value, so there's no legitimate {@code GloballyScoped} lookup either.
 */
public interface PlayerRepository extends MongoRepository<Player, String> {}
