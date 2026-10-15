package com.squadpulse.match;

import com.squadpulse.auth.ClubFootballMappings;
import com.squadpulse.auth.FootballOrgIlIds;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.squad.Player;
import com.squadpulse.squad.PlayerRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * The club's scraped player season statistics with their links and name suggestions, read-only
 * (KAN-72). Which team and season to show comes only from the caller's own club mapping ({@link
 * ClubFootballMappings#currentClubMapping()}); every lookup is club-scoped. A link whose {@code
 * Player} no longer exists counts as no link (see {@link PlayerStatsLinkService}).
 */
@Service
class PlayerStatsReadService {

  private final ClubFootballMappings mappings;
  private final PlayerSeasonStatsSnapshotRepository snapshotRepository;
  private final PlayerStatsLinkRepository linkRepository;
  private final PlayerRepository playerRepository;
  private final ClubContext clubContext;

  PlayerStatsReadService(
      ClubFootballMappings mappings,
      PlayerSeasonStatsSnapshotRepository snapshotRepository,
      PlayerStatsLinkRepository linkRepository,
      PlayerRepository playerRepository,
      ClubContext clubContext) {
    this.mappings = mappings;
    this.snapshotRepository = snapshotRepository;
    this.linkRepository = linkRepository;
    this.playerRepository = playerRepository;
    this.clubContext = clubContext;
  }

  PlayerStatsResponse read() {
    Optional<FootballOrgIlIds> mapping = mappings.currentClubMapping();
    if (mapping.isEmpty()) {
      return new PlayerStatsResponse(MatchDataState.NOT_MAPPED, null, List.of());
    }
    Optional<PlayerSeasonStatsSnapshot> snapshot = snapshotFor(mapping.get());
    if (snapshot.isEmpty()) {
      return new PlayerStatsResponse(MatchDataState.NO_DATA, null, List.of());
    }
    return new PlayerStatsResponse(
        MatchDataState.OK, snapshot.get().getFetchedAt(), rows(snapshot.get().getPlayers()));
  }

  /**
   * The row with this opaque id, as {@link #read} shows it.
   *
   * @throws NotFoundException if it isn't in the club's current snapshot
   */
  PlayerStatsResponse.Row row(String id) {
    return read().players().stream()
        .filter(row -> row.id().equals(id))
        .findFirst()
        .orElseThrow(PlayerStatsReadService::rowNotFound);
  }

  /**
   * The stored row with this opaque id in the club's current snapshot.
   *
   * @throws NotFoundException if the club isn't mapped, has no snapshot, or it has no such row
   */
  StoredPlayerStats storedRow(String id) {
    return mappings
        .currentClubMapping()
        .flatMap(this::snapshotFor)
        .flatMap(
            snapshot ->
                snapshot.getPlayers().stream().filter(row -> row.opaqueId().equals(id)).findFirst())
        .orElseThrow(PlayerStatsReadService::rowNotFound);
  }

  private Optional<PlayerSeasonStatsSnapshot> snapshotFor(FootballOrgIlIds ids) {
    return snapshotRepository.findByClubIdAndTeamIdAndSeasonId(
        clubContext.requireClubId(), ids.teamId(), ids.seasonId());
  }

  /**
   * The current club's linked players by {@code player_id}, for any scraped data keyed by it (the
   * statistics, KAN-79's game details). A link whose {@code Player} is gone (a delete that raced a
   * link) counts as no link.
   */
  Map<Integer, Player> linkedPlayers() {
    return linkedPlayers(allPlayers());
  }

  private Map<String, Player> allPlayers() {
    return playerRepository.findAll().stream()
        .collect(Collectors.toMap(Player::getId, Function.identity()));
  }

  private Map<Integer, Player> linkedPlayers(Map<String, Player> players) {
    Map<Integer, Player> linked = new HashMap<>();
    for (PlayerStatsLink link : linkRepository.findAll()) {
      Player player = players.get(link.getPlayerId());
      if (player != null) {
        linked.put(link.getSourcePlayerId(), player);
      }
    }
    return linked;
  }

  private List<PlayerStatsResponse.Row> rows(List<StoredPlayerStats> stored) {
    Map<String, Player> players = allPlayers();
    Map<Integer, Player> linked = linkedPlayers(players);
    Set<String> linkedPlayerIds = new HashSet<>();
    linked.values().forEach(player -> linkedPlayerIds.add(player.getId()));

    Map<String, String> unlinkedNames = new LinkedHashMap<>();
    stored.stream()
        .filter(row -> !linked.containsKey(row.sourcePlayerId()))
        .forEach(row -> unlinkedNames.put(row.opaqueId(), row.name()));
    Map<String, String> candidateNames = new LinkedHashMap<>();
    players.values().stream()
        .filter(player -> !linkedPlayerIds.contains(player.getId()))
        .forEach(player -> candidateNames.put(player.getId(), player.getFullName()));
    Map<String, String> suggestions = NameSuggestions.suggest(unlinkedNames, candidateNames);

    return stored.stream()
        .map(
            row -> {
              Player linkedPlayer = linked.get(row.sourcePlayerId());
              String suggested = suggestions.get(row.opaqueId());
              return new PlayerStatsResponse.Row(
                  row.opaqueId(),
                  row.name(),
                  row.games(),
                  row.goals(),
                  row.yellowCardsLeagueAndCup(),
                  row.yellowCardsToto(),
                  row.redCards(),
                  row.starts(),
                  row.cameOnAsSub(),
                  row.subbedOff(),
                  row.minutes(),
                  linkedPlayer == null ? null : PlayerStatsResponse.PlayerRef.of(linkedPlayer),
                  suggested == null
                      ? null
                      : PlayerStatsResponse.PlayerRef.of(players.get(suggested)));
            })
        .toList();
  }

  private static NotFoundException rowNotFound() {
    return new NotFoundException("Player statistics not found");
  }
}
