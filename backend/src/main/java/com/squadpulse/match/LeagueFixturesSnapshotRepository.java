package com.squadpulse.match;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Club-scoped repository for {@link LeagueFixturesSnapshot}: its standard methods go through the
 * {@code clubId} isolation layer; the one finder takes the clubId explicitly, which callers read
 * from {@code ClubContext}.
 */
interface LeagueFixturesSnapshotRepository extends MongoRepository<LeagueFixturesSnapshot, String> {

  /**
   * @param clubId only ever {@code ClubContext.requireClubId()}
   */
  Optional<LeagueFixturesSnapshot> findByClubIdAndTeamIdAndSeasonId(
      String clubId, Integer teamId, Integer seasonId);
}
