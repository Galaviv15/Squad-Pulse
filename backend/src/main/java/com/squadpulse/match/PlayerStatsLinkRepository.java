package com.squadpulse.match;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Club-scoped repository for {@link PlayerStatsLink}: the standard methods go through the {@code
 * clubId} isolation layer; every finder and delete here takes the clubId explicitly, which callers
 * read from {@code ClubContext} only.
 */
interface PlayerStatsLinkRepository extends MongoRepository<PlayerStatsLink, String> {

  Optional<PlayerStatsLink> findByClubIdAndSourcePlayerId(String clubId, Integer sourcePlayerId);

  Optional<PlayerStatsLink> findByClubIdAndPlayerId(String clubId, String playerId);

  long deleteByClubIdAndSourcePlayerId(String clubId, Integer sourcePlayerId);

  long deleteByClubIdAndPlayerId(String clubId, String playerId);
}
