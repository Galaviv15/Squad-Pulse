package com.squadpulse.match;

import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Club-scoped repository for {@link CupFixturesSnapshot} (KAN-84): its standard methods go through
 * the {@code clubId} isolation layer; the finders take the clubId explicitly, which callers read
 * from {@code ClubContext}.
 */
interface CupFixturesSnapshotRepository extends MongoRepository<CupFixturesSnapshot, String> {

  /**
   * @param clubId only ever {@code ClubContext.requireClubId()}
   */
  Optional<CupFixturesSnapshot> findByClubIdAndTeamIdAndSeasonIdAndCompetition(
      String clubId, Integer teamId, Integer seasonId, CupCompetition competition);

  /**
   * Every competition's snapshot of the club for this team and season.
   *
   * @param clubId only ever {@code ClubContext.requireClubId()}
   */
  List<CupFixturesSnapshot> findByClubIdAndTeamIdAndSeasonId(
      String clubId, Integer teamId, Integer seasonId);
}
