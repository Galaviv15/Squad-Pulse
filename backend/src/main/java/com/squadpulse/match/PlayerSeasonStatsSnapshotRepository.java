package com.squadpulse.match;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Club-scoped repository for {@link PlayerSeasonStatsSnapshot}: its standard methods go through the
 * {@code clubId} isolation layer; the one finder takes the clubId explicitly, which callers read
 * from {@code ClubContext}. Written only by {@link ScrapeIngestService}.
 */
interface PlayerSeasonStatsSnapshotRepository
    extends MongoRepository<PlayerSeasonStatsSnapshot, String> {

  /**
   * @param clubId only ever {@code ClubContext.requireClubId()}
   */
  Optional<PlayerSeasonStatsSnapshot> findByClubIdAndTeamIdAndSeasonId(
      String clubId, Integer teamId, Integer seasonId);
}
