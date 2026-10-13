package com.squadpulse.match;

import com.mongodb.MongoException;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.squad.CreatePlayerRequest;
import com.squadpulse.squad.Player;
import com.squadpulse.squad.PlayerCreation;
import com.squadpulse.squad.PlayerRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a squad player from a scraped statistics row and links the row to it, in one step
 * (KAN-77): for a new club, whose squad is empty when its first scrape arrives.
 *
 * <p><b>One MongoDB transaction, never a compensating delete.</b> The {@code Player} insert and the
 * link write run in one {@link TransactionTemplate} on the {@code MongoTransactionManager}, so they
 * both happen or neither does. Creating first and deleting the player again if the link fails would
 * leave a window in which the player is visible (and could be edited, linked or photographed by
 * someone else), and a crash in between would leave it for good. Both writes go through the
 * club-scoped repositories, which join the transaction: the clubId still comes only from {@link
 * ClubContext}, and the stamping and cross-club checks apply as everywhere else.
 *
 * <p><b>It refuses a linked row</b> ({@link StatsRowAlreadyLinkedException}), where {@code PUT
 * /matches/player-stats/{id}/link} replaces the link: that endpoint is an explicit choice between
 * two existing players, while this one is reached from a row shown as unlinked; replacing here
 * would silently take the row away from a player someone linked meanwhile and leave a new, unlinked
 * duplicate of them in the squad. A link to a deleted {@code Player} counts as none (see {@link
 * PlayerStatsLinkService}) and is replaced. The check runs before the transaction (the common case
 * costs no transaction) and again inside it, on the link document the transaction then writes, so a
 * link made in between is refused too, rolling the player back.
 *
 * <p><b>A race lost inside the transaction</b> is decided after the transaction has been aborted,
 * outside it: a read inside an aborted transaction fails, so {@code
 * PlayerStatsLinkService#afterLostRace}'s re-read can't be reused. A duplicate key on the row's
 * link → one re-read of the row's link: linked to an existing player → {@link
 * StatsRowAlreadyLinkedException}, otherwise {@code 409 CONCURRENT_MODIFICATION}. A write conflict
 * (two transactions writing the same link document, or a link committed after this transaction's
 * snapshot) → {@code 409 CONCURRENT_MODIFICATION}. The installed Spring Data MongoDB translates a
 * write conflict (server code 112, label {@code TransientTransactionError}) to a plain {@link
 * DataIntegrityViolationException}, which would otherwise be a 500. A taken jersey number is {@code
 * squad}'s {@code JerseyNumberTakenException}, unchanged.
 *
 * <p><b>No automatic retry.</b> A retry would find the row linked and answer 409 anyway, and
 * retrying a user's create is how a squad ends up with two copies of a player.
 */
@Service
class StatsPlayerCreationService {

  /** MongoDB's {@code WriteConflict} server error code. */
  private static final int WRITE_CONFLICT = 112;

  private final PlayerStatsReadService readService;
  private final PlayerStatsLinkRepository linkRepository;
  private final PlayerRepository playerRepository;
  private final PlayerCreation playerCreation;
  private final ClubContext clubContext;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  @Autowired
  StatsPlayerCreationService(
      PlayerStatsReadService readService,
      PlayerStatsLinkRepository linkRepository,
      PlayerRepository playerRepository,
      PlayerCreation playerCreation,
      ClubContext clubContext,
      PlatformTransactionManager transactionManager) {
    this(
        readService,
        linkRepository,
        playerRepository,
        playerCreation,
        clubContext,
        transactionManager,
        Clock.systemUTC());
  }

  StatsPlayerCreationService(
      PlayerStatsReadService readService,
      PlayerStatsLinkRepository linkRepository,
      PlayerRepository playerRepository,
      PlayerCreation playerCreation,
      ClubContext clubContext,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.readService = readService;
    this.linkRepository = linkRepository;
    this.playerRepository = playerRepository;
    this.playerCreation = playerCreation;
    this.clubContext = clubContext;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /**
   * Creates a player of the club from {@code request} (already validated) and links the scraped
   * player with opaque id {@code id} to it, atomically.
   *
   * @return the created player
   * @throws NotFoundException if the scraped player isn't in the club's current statistics
   * @throws StatsRowAlreadyLinkedException if it's linked to an existing player
   * @throws OptimisticLockingFailureException if a concurrent write to its link won
   */
  Player createLinked(String id, CreatePlayerRequest request) {
    StoredPlayerStats row = readService.storedRow(id);
    String clubId = clubContext.requireClubId();
    int sourcePlayerId = row.sourcePlayerId();
    if (liveLink(clubId, sourcePlayerId).isPresent()) {
      throw new StatsRowAlreadyLinkedException();
    }

    try {
      return transactionTemplate.execute(
          status -> {
            Player player = playerCreation.create(request);
            // Read inside the transaction: the link document it then writes (a dangling one is
            // replaced). No PLAYER_ALREADY_LINKED check: the player was created a moment ago, in
            // this transaction, so no link can name it yet.
            PlayerStatsLink link =
                linkRepository
                    .findByClubIdAndSourcePlayerId(clubId, sourcePlayerId)
                    .orElseGet(PlayerStatsLink::new);
            if (link.getPlayerId() != null && playerRepository.existsById(link.getPlayerId())) {
              throw new StatsRowAlreadyLinkedException(); // linked since the check above
            }
            link.setSourcePlayerId(sourcePlayerId);
            link.setPlayerId(player.getId());
            link.setLinkedAt(Instant.now(clock));
            linkRepository.save(link);
            return player;
          });
    } catch (DuplicateKeyException e) {
      // Only the row's link can clash here (see above); the transaction is aborted by now.
      if (liveLink(clubId, sourcePlayerId).isPresent()) {
        throw new StatsRowAlreadyLinkedException();
      }
      throw new OptimisticLockingFailureException("A concurrent link of the same row won", e);
    } catch (DataIntegrityViolationException e) {
      if (isWriteConflict(e)) {
        throw new OptimisticLockingFailureException("A concurrent link of the same row won", e);
      }
      throw e;
    }
  }

  /** The row's link, if its {@code Player} still exists: a dangling link counts as none. */
  private Optional<PlayerStatsLink> liveLink(String clubId, int sourcePlayerId) {
    return linkRepository
        .findByClubIdAndSourcePlayerId(clubId, sourcePlayerId)
        .filter(link -> playerRepository.existsById(link.getPlayerId()));
  }

  private static boolean isWriteConflict(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof MongoException mongo
          && (mongo.getCode() == WRITE_CONFLICT
              || mongo.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL))) {
        return true;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return false;
  }
}
