package com.squadpulse.match;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Club-scoped repository for {@link GameDetails}: its standard methods ({@code findById}, ...) go
 * through the {@code clubId} isolation layer; every finder here takes the clubId explicitly, which
 * callers read from {@code ClubContext} only. Written only by {@link ScrapeIngestService}.
 */
interface GameDetailsRepository extends MongoRepository<GameDetails, String> {

  Optional<GameDetails> findByClubIdAndTeamIdAndSeasonIdAndGameId(
      String clubId, Integer teamId, Integer seasonId, Integer gameId);

  List<GameDetails> findByClubIdAndTeamIdAndSeasonId(
      String clubId, Integer teamId, Integer seasonId);
}
