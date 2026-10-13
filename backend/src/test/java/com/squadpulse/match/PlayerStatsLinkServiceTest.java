package com.squadpulse.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squadpulse.common.ClubContext;
import com.squadpulse.squad.Player;
import com.squadpulse.squad.PlayerRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * How {@link PlayerStatsLinkService} decides a link write that lost a race (KAN-72): by re-reading
 * the player's link, never by reading the exception. The duplicate key here has no cause and a
 * message naming no index, so nothing but the re-read can decide.
 */
class PlayerStatsLinkServiceTest {

  private static final String CLUB = "club-a";
  private static final String ROW = "row-1";
  private static final String PLAYER = "player-1";
  private static final int SOURCE = 900001;

  private final PlayerStatsReadService readService = mock(PlayerStatsReadService.class);
  private final PlayerStatsLinkRepository linkRepository = mock(PlayerStatsLinkRepository.class);
  private final PlayerRepository playerRepository = mock(PlayerRepository.class);
  private final ClubContext clubContext = new ClubContext();
  private final PlayerStatsLinkService service =
      new PlayerStatsLinkService(readService, linkRepository, playerRepository, clubContext);
  private final PlayerStatsResponse.Row row =
      new PlayerStatsResponse.Row(ROW, "כהן דניאל", 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);

  @BeforeEach
  void setUp() {
    clubContext.setClubId(CLUB);
    when(readService.storedRow(ROW))
        .thenReturn(new StoredPlayerStats(ROW, SOURCE, "כהן דניאל", 0, 0, 0, 0, 0, 0, 0, 0, 0));
    when(readService.row(ROW)).thenReturn(row);
    when(playerRepository.findById(PLAYER)).thenReturn(Optional.of(new Player()));
    when(linkRepository.findByClubIdAndSourcePlayerId(CLUB, SOURCE)).thenReturn(Optional.empty());
    when(linkRepository.save(any(PlayerStatsLink.class)))
        .thenThrow(new DuplicateKeyException("something went wrong"));
  }

  @Test
  void theSamePairLinkedMeanwhileIsASuccess() {
    afterTheRace(link(SOURCE));

    assertThat(service.link(ROW, PLAYER)).isSameAs(row);
  }

  @Test
  void thePlayerLinkedToAnotherScrapedPlayerMeanwhileIsAlreadyLinked() {
    afterTheRace(link(SOURCE + 1));

    assertThatThrownBy(() -> service.link(ROW, PLAYER))
        .isInstanceOf(PlayerAlreadyLinkedException.class);
  }

  @Test
  void theScrapedPlayerLinkedToSomeoneElseMeanwhileIsAConcurrentModification() {
    afterTheRace(null);

    assertThatThrownBy(() -> service.link(ROW, PLAYER))
        .isInstanceOf(OptimisticLockingFailureException.class)
        .isNotInstanceOf(DuplicateKeyException.class);
  }

  /** No link before the save (the check passes); {@code after} on the re-read. */
  private void afterTheRace(PlayerStatsLink after) {
    when(linkRepository.findByClubIdAndPlayerId(CLUB, PLAYER))
        .thenReturn(Optional.empty(), Optional.ofNullable(after));
  }

  private static PlayerStatsLink link(int sourcePlayerId) {
    PlayerStatsLink link = new PlayerStatsLink();
    link.setSourcePlayerId(sourcePlayerId);
    link.setPlayerId(PLAYER);
    return link;
  }
}
