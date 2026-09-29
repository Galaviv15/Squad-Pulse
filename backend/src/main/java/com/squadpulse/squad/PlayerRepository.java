package com.squadpulse.squad;

import java.util.List;
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
public interface PlayerRepository extends MongoRepository<Player, String> {

  /**
   * A derived query, so <b>not</b> scoped by the club-scoped layer: the caller must pass {@code
   * ClubContext.requireClubId()}, never a clubId from the request.
   */
  List<Player> findByClubIdAndActive(String clubId, boolean active);
}
