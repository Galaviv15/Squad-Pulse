package com.squadpulse.match;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for the shared, {@link com.squadpulse.common.NotClubScoped} {@link
 * LeagueStandingsSnapshot}, built on plain {@code SimpleMongoRepository}. Package-private, so only
 * {@code match} can reach it; within {@code match}, only {@link ScrapeIngestService} writes through
 * it ({@code StandingsWritePathTest}).
 */
interface LeagueStandingsSnapshotRepository
    extends MongoRepository<LeagueStandingsSnapshot, String> {

  /**
   * @param leagueId on a read, only ever from the caller's own club mapping, never from a request
   */
  Optional<LeagueStandingsSnapshot> findByLeagueIdAndSeasonId(Integer leagueId, Integer seasonId);
}
