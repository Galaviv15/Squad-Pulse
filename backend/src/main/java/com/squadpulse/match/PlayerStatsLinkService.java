package com.squadpulse.match;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.NotFoundException;
import com.squadpulse.squad.PlayerRepository;
import com.squadpulse.squad.PlayerStatsLinks;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Links between the club's scraped players and its own {@code squad.Player}s (KAN-72). Everything
 * is within the caller's club: the clubId comes only from {@link ClubContext}, a scraped player is
 * found only in the club's own current snapshot (by our opaque id), and a {@code Player} only
 * through the club-scoped repository — so another club's player is a 404, the same as one that
 * doesn't exist, never a 403 that would reveal it.
 *
 * <p><b>Rules.</b> One link per scraped player and per {@code Player} in a club, guaranteed by the
 * two unique indexes on {@link PlayerStatsLink}; a write that loses a race on the {@code Player}'s
 * index is the same {@link PlayerAlreadyLinkedException} as the check before it. Linking a scraped
 * player again replaces its link (the old {@code Player} is free again); linking the same pair
 * again changes nothing. A released {@code Player} may be linked. Links are keyed by the scraped
 * player's stable {@code player_id}, so they outlive the player's absence from a scrape and a
 * season change; only an unlink removes one, from either side ({@link #unlink}, {@link
 * #removeLinkOf}).
 *
 * <p><b>A link whose {@code Player} no longer exists counts as no link</b>, everywhere: it isn't
 * shown, doesn't stop its scraped player from being suggested or linked again (the new link
 * replaces it), and doesn't hold a name back from suggestions. Permanent deletion of a {@code
 * Player} checks for a link first ({@code squad.PlayerService#delete}), but that check and a
 * concurrent link are separate writes with no transaction between them: both can pass (write skew),
 * leaving a link to a deleted player. No lock is taken to prevent it — a delete isn't
 * version-checked, and a lock across two modules for a rare, harmless race would promise more than
 * it can keep. The rule above makes the outcome harmless instead.
 */
@Service
class PlayerStatsLinkService implements PlayerStatsLinks {

  private final PlayerStatsReadService readService;
  private final PlayerStatsLinkRepository linkRepository;
  private final PlayerRepository playerRepository;
  private final ClubContext clubContext;
  private final Clock clock;

  @Autowired
  PlayerStatsLinkService(
      PlayerStatsReadService readService,
      PlayerStatsLinkRepository linkRepository,
      PlayerRepository playerRepository,
      ClubContext clubContext) {
    this(readService, linkRepository, playerRepository, clubContext, Clock.systemUTC());
  }

  PlayerStatsLinkService(
      PlayerStatsReadService readService,
      PlayerStatsLinkRepository linkRepository,
      PlayerRepository playerRepository,
      ClubContext clubContext,
      Clock clock) {
    this.readService = readService;
    this.linkRepository = linkRepository;
    this.playerRepository = playerRepository;
    this.clubContext = clubContext;
    this.clock = clock;
  }

  /**
   * Links the scraped player with opaque id {@code id} to the club's {@code playerId}, replacing
   * its link if it has one.
   *
   * @return the row as {@code GET /matches/player-stats} shows it
   * @throws NotFoundException if the scraped player isn't in the club's current statistics, or
   *     there's no such player in the club
   * @throws PlayerAlreadyLinkedException if the player is linked to another scraped player
   */
  PlayerStatsResponse.Row link(String id, String playerId) {
    StoredPlayerStats row = readService.storedRow(id);
    if (playerRepository.findById(playerId).isEmpty()) {
      throw new NotFoundException("Player not found");
    }
    String clubId = clubContext.requireClubId();
    int sourcePlayerId = row.sourcePlayerId();

    // The player exists, so a link of theirs isn't dangling.
    Optional<PlayerStatsLink> ofPlayer = linkRepository.findByClubIdAndPlayerId(clubId, playerId);
    if (ofPlayer.isPresent()) {
      if (ofPlayer.get().getSourcePlayerId() != sourcePlayerId) {
        throw new PlayerAlreadyLinkedException();
      }
      return readService.row(id); // the same pair: nothing to do
    }

    PlayerStatsLink link =
        linkRepository
            .findByClubIdAndSourcePlayerId(clubId, sourcePlayerId)
            .orElseGet(PlayerStatsLink::new);
    link.setSourcePlayerId(sourcePlayerId);
    link.setPlayerId(playerId);
    link.setLinkedAt(Instant.now(clock));
    try {
      linkRepository.save(link);
    } catch (DuplicateKeyException e) {
      throw translate(e);
    }
    return readService.row(id);
  }

  /**
   * Removes the link of the scraped player with opaque id {@code id}, if any.
   *
   * @throws NotFoundException if it isn't in the club's current statistics
   */
  void unlink(String id) {
    StoredPlayerStats row = readService.storedRow(id);
    linkRepository.deleteByClubIdAndSourcePlayerId(
        clubContext.requireClubId(), row.sourcePlayerId());
  }

  @Override
  public boolean isLinked(String playerId) {
    return linkRepository
        .findByClubIdAndPlayerId(clubContext.requireClubId(), playerId)
        .isPresent();
  }

  @Override
  public void removeLinkOf(String playerId) {
    linkRepository.deleteByClubIdAndPlayerId(clubContext.requireClubId(), playerId);
  }

  /**
   * A race lost on the {@code Player}'s index: someone linked that player meanwhile → {@link
   * PlayerAlreadyLinkedException}. Lost on the scraped player's index (two first links of the same
   * scraped player at once) → {@code 409 CONCURRENT_MODIFICATION}: reload and decide again.
   */
  private static RuntimeException translate(DuplicateKeyException e) {
    String index = violatedIndex(e);
    if (PlayerStatsLink.PLAYER_INDEX.equals(index)) {
      return new PlayerAlreadyLinkedException();
    }
    if (PlayerStatsLink.SOURCE_PLAYER_INDEX.equals(index)) {
      return new OptimisticLockingFailureException("A concurrent link of the same player won", e);
    }
    return e;
  }

  /** The index an E11000 names ({@code "index: <name> dup key"}), or {@code null}. */
  private static String violatedIndex(DuplicateKeyException e) {
    if (!(e.getCause() instanceof MongoWriteException writeException)
        || writeException.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
      return null;
    }
    String message = writeException.getError().getMessage();
    for (String index :
        new String[] {PlayerStatsLink.PLAYER_INDEX, PlayerStatsLink.SOURCE_PLAYER_INDEX}) {
      if (message.contains("index: " + index + " dup key")) {
        return index;
      }
    }
    return null;
  }
}
