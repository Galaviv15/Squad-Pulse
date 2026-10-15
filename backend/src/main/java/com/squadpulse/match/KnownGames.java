package com.squadpulse.match;

import com.squadpulse.common.ClubContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Which of the current club's games the scraper need not fetch again (KAN-79), for {@code
 * scrapingintegration}'s trigger: those whose {@link GameDetails} are stored and those given up
 * after {@value GameDetailAttempts#GIVE_UP_AFTER} counted failures ({@link GameDetailAttempts}).
 * Club-scoped reads only: the clubId comes from {@link ClubContext}, the team and season from the
 * caller (the club's own mapping), so it works the same in an HTTP request and in {@code
 * ClubContext.callAs} (a scheduled job).
 */
@Service
public class KnownGames {

  private final GameDetailsRepository detailsRepository;
  private final GameDetailAttemptsRepository attemptsRepository;
  private final ClubContext clubContext;

  KnownGames(
      GameDetailsRepository detailsRepository,
      GameDetailAttemptsRepository attemptsRepository,
      ClubContext clubContext) {
    this.detailsRepository = detailsRepository;
    this.attemptsRepository = attemptsRepository;
    this.clubContext = clubContext;
  }

  /**
   * The current club's stored and given-up {@code game_id}s for this team and season, ascending,
   * without duplicates.
   */
  public List<Integer> knownGameIds(int teamId, int seasonId) {
    Set<Integer> ids = new TreeSet<>(stored(teamId, seasonId).keySet());
    ids.addAll(givenUp(teamId, seasonId));
    return List.copyOf(ids);
  }

  /** The current club's stored details for this team and season, by {@code game_id}. */
  Map<Integer, GameDetails> stored(int teamId, int seasonId) {
    return detailsRepository
        .findByClubIdAndTeamIdAndSeasonId(clubContext.requireClubId(), teamId, seasonId)
        .stream()
        .collect(Collectors.toMap(GameDetails::getGameId, Function.identity()));
  }

  /** The current club's given-up {@code game_id}s for this team and season. */
  Set<Integer> givenUp(int teamId, int seasonId) {
    return attemptsRepository
        .findByClubIdAndTeamIdAndSeasonId(clubContext.requireClubId(), teamId, seasonId)
        .stream()
        .filter(GameDetailAttempts::givenUp)
        .map(GameDetailAttempts::getGameId)
        .collect(Collectors.toSet());
  }
}
