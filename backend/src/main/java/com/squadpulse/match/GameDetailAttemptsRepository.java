package com.squadpulse.match;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Club-scoped repository for {@link GameDetailAttempts}: every finder and delete here takes the
 * clubId explicitly, which callers read from {@code ClubContext} only. Written only by {@link
 * ScrapeIngestService}.
 */
interface GameDetailAttemptsRepository extends MongoRepository<GameDetailAttempts, String> {

  Optional<GameDetailAttempts> findByClubIdAndTeamIdAndSeasonIdAndGameId(
      String clubId, Integer teamId, Integer seasonId, Integer gameId);

  List<GameDetailAttempts> findByClubIdAndTeamIdAndSeasonId(
      String clubId, Integer teamId, Integer seasonId);

  long deleteByClubIdAndTeamIdAndSeasonIdAndGameId(
      String clubId, Integer teamId, Integer seasonId, Integer gameId);
}
