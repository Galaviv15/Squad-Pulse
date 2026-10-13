package com.squadpulse.match;

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
 * two unique indexes on {@link PlayerStatsLink}. Linking a scraped player again replaces its link
 * (the old {@code Player} is free again); linking the same pair again changes nothing.
 *
 * <p><b>A write that loses a race</b> (a duplicate key on either index) is decided by re-reading
 * the {@code Player}'s link once, never by reading the exception or the server's message: linked to
 * this scraped player → the same outcome as ours, {@code 200} (a double click); linked to another
 * scraped player → {@link PlayerAlreadyLinkedException}; otherwise this scraped player was linked
 * to someone else meanwhile → {@code 409 CONCURRENT_MODIFICATION}. No duplicate key escapes.
 *
 * <p><b>Replacing an existing link is last write wins.</b> {@link PlayerStatsLink} has no
 * {@code @Version}: two requests replacing the same scraped player's link with two different {@code
 * Player}s at once both update the one document, and the later save stands (each request answers
 * {@code 200} with what it wrote). Unless both pick the same {@code Player}, no unique index is
 * involved. A released {@code Player} may be linked. Links are keyed by the scraped player's stable
 * {@code player_id}, so they outlive the player's absence from a scrape and a season change; only
 * an unlink removes one, from either side ({@link #unlink}, {@link #removeLinkOf}).
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
      return afterLostRace(clubId, id, sourcePlayerId, playerId, e);
    }
    return readService.row(id);
  }

  /** See the class comment: decided by one re-read of the player's link. */
  private PlayerStatsResponse.Row afterLostRace(
      String clubId, String id, int sourcePlayerId, String playerId, DuplicateKeyException e) {
    Optional<PlayerStatsLink> ofPlayer = linkRepository.findByClubIdAndPlayerId(clubId, playerId);
    if (ofPlayer.isPresent()) {
      if (ofPlayer.get().getSourcePlayerId() == sourcePlayerId) {
        return readService.row(id);
      }
      throw new PlayerAlreadyLinkedException();
    }
    throw new OptimisticLockingFailureException("A concurrent link of the same player won", e);
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
}
