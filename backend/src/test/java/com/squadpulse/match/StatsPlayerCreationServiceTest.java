package com.squadpulse.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.squadpulse.common.ClubContext;
import com.squadpulse.squad.CreatePlayerRequest;
import com.squadpulse.squad.Player;
import com.squadpulse.squad.PlayerCreation;
import com.squadpulse.squad.PlayerRepository;
import com.squadpulse.squad.Position;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * The decisions of {@link StatsPlayerCreationService} (KAN-77) that a real database rarely or never
 * reaches: a live link refused before any transaction is opened, and a duplicate key decided by one
 * re-read after the abort, never by the exception. The transaction itself, its rollbacks and the
 * write conflicts are proved on a real replica set ({@code StatsPlayerCreationIntegrationTest}).
 */
class StatsPlayerCreationServiceTest {

  private static final String CLUB = "club-a";
  private static final String ROW = "row-1";
  private static final int SOURCE = 900001;
  private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

  private final PlayerStatsReadService readService = mock(PlayerStatsReadService.class);
  private final PlayerStatsLinkRepository linkRepository = mock(PlayerStatsLinkRepository.class);
  private final PlayerRepository playerRepository = mock(PlayerRepository.class);
  private final PlayerCreation playerCreation = mock(PlayerCreation.class);
  private final PlatformTransactionManager transactionManager =
      mock(PlatformTransactionManager.class);
  private final ClubContext clubContext = new ClubContext();
  private final StatsPlayerCreationService service =
      new StatsPlayerCreationService(
          readService,
          linkRepository,
          playerRepository,
          playerCreation,
          clubContext,
          transactionManager,
          Clock.fixed(NOW, ZoneOffset.UTC));
  private final CreatePlayerRequest request =
      new CreatePlayerRequest(
          "דניאל כהן", Position.ST, null, null, LocalDate.of(1995, 5, 20), null, null, null, null);
  private final Player created = new Player();

  @BeforeEach
  void setUp() {
    clubContext.setClubId(CLUB);
    created.setId("new-player");
    when(readService.storedRow(ROW))
        .thenReturn(new StoredPlayerStats(ROW, SOURCE, "כהן דניאל", 0, 0, 0, 0, 0, 0, 0, 0, 0));
    when(linkRepository.findByClubIdAndSourcePlayerId(CLUB, SOURCE)).thenReturn(Optional.empty());
    when(playerCreation.create(request)).thenReturn(created);
    when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
  }

  @Test
  void createsAndLinksInOneTransaction() {
    assertThat(service.createLinked(ROW, request)).isSameAs(created);

    ArgumentCaptor<PlayerStatsLink> saved = ArgumentCaptor.forClass(PlayerStatsLink.class);
    verify(linkRepository).save(saved.capture());
    assertThat(saved.getValue().getPlayerId()).isEqualTo("new-player");
    assertThat(saved.getValue().getSourcePlayerId()).isEqualTo(SOURCE);
    assertThat(saved.getValue().getLinkedAt()).isEqualTo(NOW);
    verify(transactionManager).commit(any(TransactionStatus.class));
  }

  @Test
  void aLiveLinkIsRefusedBeforeAnyTransaction() {
    when(linkRepository.findByClubIdAndSourcePlayerId(CLUB, SOURCE))
        .thenReturn(Optional.of(link("existing")));
    when(playerRepository.existsById("existing")).thenReturn(true);

    assertThatThrownBy(() -> service.createLinked(ROW, request))
        .isInstanceOf(StatsRowAlreadyLinkedException.class);
    verifyNoInteractions(transactionManager, playerCreation);
  }

  @Test
  void aDanglingLinkIsReplaced() {
    PlayerStatsLink dangling = link("deleted");
    when(linkRepository.findByClubIdAndSourcePlayerId(CLUB, SOURCE))
        .thenReturn(Optional.of(dangling));

    service.createLinked(ROW, request);

    verify(linkRepository).save(dangling);
    assertThat(dangling.getPlayerId()).isEqualTo("new-player");
  }

  @Test
  void aDuplicateKeyWithTheRowLinkedMeanwhileIsAlreadyLinked() {
    when(linkRepository.save(any(PlayerStatsLink.class)))
        .thenAnswer(
            invocation -> {
              // What the re-read after the abort will see.
              when(linkRepository.findByClubIdAndSourcePlayerId(CLUB, SOURCE))
                  .thenReturn(Optional.of(link("someone-else")));
              when(playerRepository.existsById("someone-else")).thenReturn(true);
              throw new DuplicateKeyException("something went wrong");
            });

    assertThatThrownBy(() -> service.createLinked(ROW, request))
        .isInstanceOf(StatsRowAlreadyLinkedException.class);
    verify(transactionManager).rollback(any(TransactionStatus.class));
    verify(transactionManager, never()).commit(any(TransactionStatus.class));
  }

  @Test
  void aDuplicateKeyWithNoLiveLinkAfterwardsIsAConcurrentModification() {
    when(linkRepository.save(any(PlayerStatsLink.class)))
        .thenThrow(new DuplicateKeyException("something went wrong"));

    assertThatThrownBy(() -> service.createLinked(ROW, request))
        .isInstanceOf(OptimisticLockingFailureException.class);
    verify(transactionManager).rollback(any(TransactionStatus.class));
  }

  @Test
  void anotherDataIntegrityViolationIsNotTakenForARace() {
    DataIntegrityViolationException other = new DataIntegrityViolationException("other");
    when(linkRepository.save(any(PlayerStatsLink.class))).thenThrow(other);

    assertThatThrownBy(() -> service.createLinked(ROW, request)).isSameAs(other);
  }

  private static PlayerStatsLink link(String playerId) {
    PlayerStatsLink link = new PlayerStatsLink();
    link.setSourcePlayerId(SOURCE);
    link.setPlayerId(playerId);
    return link;
  }
}
