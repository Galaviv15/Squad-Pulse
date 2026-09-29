package com.squadpulse.squad;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.squadpulse.common.ClubContext;
import com.squadpulse.common.NotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The club's roster: list (with filters), get, create and update players (see docs/spec.md section
 * 05). Everything is within the caller's club, whose id comes only from {@link ClubContext}.
 *
 * <p><b>Club isolation.</b> A player is always loaded through the club-scoped {@link
 * PlayerRepository#findById} first, so another club's player is simply not found — the same 404 as
 * an id that doesn't exist. The club check inside {@code save} is a backstop that never answers.
 *
 * <p><b>Filters run in memory.</b> The club's players are loaded through a club-scoped repository
 * method and filtered and sorted in Java. A hand-built {@code MongoTemplate} query would bypass the
 * club-scoped layer, and nothing would catch a missing {@code clubId} criterion in it. A squad is
 * 30-40 players, so this costs nothing.
 *
 * <p><b>Jersey numbers.</b> Uniqueness is left entirely to the {@value Player#JERSEY_NUMBER_INDEX}
 * index: a pre-check query couldn't stop two concurrent writes from both passing it. A write the
 * index rejects becomes a {@link JerseyNumberTakenException}; any other duplicate key is rethrown
 * unchanged.
 *
 * <p><b>Edits are refused, never retried, on a conflict.</b> An update is a multi-field form edit
 * based on what the user saw, so unlike {@code auth.UserWriteRetry}, reapplying it on top of a
 * newer state would silently overwrite someone else's change. The client's {@code version} is
 * compared to the stored one before any write; a race lost between that check and the save fails
 * the save's own version condition. Both are the same {@link StalePlayerVersionException}: to the
 * client they're the same situation — someone else saved first — with the same remedy, reload.
 */
@Service
class PlayerService {

  /**
   * Primary position in enum order (GK → ST), then jersey number with players without one last,
   * then name. The id is only a final tie-break, so the order is fully deterministic.
   */
  static final Comparator<Player> SQUAD_ORDER =
      Comparator.comparing(
              Player::getPrimaryPosition, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Player::getJerseyNumber, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Player::getFullName, Comparator.nullsLast(Comparator.naturalOrder()))
          .thenComparing(Player::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  /** How MongoDB names the violated index in an E11000 message: {@code "index: <name> dup key"}. */
  private static final Pattern DUPLICATE_KEY_INDEX = Pattern.compile("\\bindex: (\\S+) dup key");

  private final PlayerRepository playerRepository;
  private final ClubContext clubContext;
  private final Clock clock;

  /**
   * Ages are computed in the JVM's default time zone — the same clock Hibernate Validator's default
   * {@code ClockProvider} gives {@code @AdultAge}, so "18 years old" means the same in validation
   * and in the age filter.
   */
  @Autowired
  PlayerService(PlayerRepository playerRepository, ClubContext clubContext) {
    this(playerRepository, clubContext, Clock.systemDefaultZone());
  }

  /** For tests: {@code clock} decides "today" for the age filters. */
  PlayerService(PlayerRepository playerRepository, ClubContext clubContext, Clock clock) {
    this.playerRepository = playerRepository;
    this.clubContext = clubContext;
    this.clock = clock;
  }

  /**
   * @throws InvalidAgeRangeException if {@code minAge > maxAge}
   */
  List<Player> list(PlayerFilter filter) {
    if (filter.minAge() != null && filter.maxAge() != null && filter.minAge() > filter.maxAge()) {
      throw new InvalidAgeRangeException();
    }
    LocalDate today = LocalDate.now(clock);
    return load(filter.status()).stream()
        .filter(filter.status()::matches)
        .filter(player -> matches(filter, player, today))
        .sorted(SQUAD_ORDER)
        .toList();
  }

  /**
   * Released players are returned too, with {@code active == false}.
   *
   * @throws NotFoundException if there's no such player in the caller's club
   */
  Player get(String id) {
    return playerRepository.findById(id).orElseThrow(PlayerService::notFound);
  }

  /**
   * @throws JerseyNumberTakenException if another active player in the club has that number
   */
  Player create(CreatePlayerRequest request) {
    Player player = new Player();
    player.setFullName(request.fullName());
    player.setPrimaryPosition(request.primaryPosition());
    player.setSecondaryPosition(request.secondaryPosition());
    player.setJerseyNumber(request.jerseyNumber());
    player.setDateOfBirth(request.dateOfBirth());
    player.setHeightCm(request.heightCm());
    player.setWeightKg(request.weightKg());
    player.setPreferredFoot(request.preferredFoot());
    if (request.medicalStatus() != null) {
      player.setMedicalStatus(request.medicalStatus());
    }
    // clubId is deliberately left unset: the club-scoped repository stamps the caller's clubId from
    // ClubContext. insert, not save, so this can only ever create a document.
    try {
      return playerRepository.insert(player);
    } catch (DuplicateKeyException e) {
      throw translateDuplicateKey(e, player);
    }
  }

  /**
   * Replaces every editable field; an optional field left {@code null} clears the stored value.
   *
   * @throws NotFoundException if there's no such player in the caller's club
   * @throws ReleasedPlayerException if the player has been released
   * @throws StalePlayerVersionException if {@code request.version()} isn't the stored version
   * @throws JerseyNumberTakenException if another active player in the club has that number
   * @throws StalePlayerVersionException also if the player was saved by someone else between the
   *     version check and this save
   */
  Player update(String id, UpdatePlayerRequest request) {
    Player player = get(id);
    if (!player.isActive()) {
      throw new ReleasedPlayerException();
    }
    // Compared, never copied onto the entity: Player.version is Spring Data's alone to manage.
    if (!Objects.equals(request.version(), player.getVersion())) {
      throw new StalePlayerVersionException();
    }
    player.setFullName(request.fullName());
    player.setPrimaryPosition(request.primaryPosition());
    player.setSecondaryPosition(request.secondaryPosition());
    player.setJerseyNumber(request.jerseyNumber());
    player.setDateOfBirth(request.dateOfBirth());
    player.setHeightCm(request.heightCm());
    player.setWeightKg(request.weightKg());
    player.setPreferredFoot(request.preferredFoot());
    player.setMedicalStatus(request.medicalStatus());
    try {
      return playerRepository.save(player);
    } catch (OptimisticLockingFailureException e) {
      throw new StalePlayerVersionException();
    } catch (DuplicateKeyException e) {
      throw translateDuplicateKey(e, player);
    }
  }

  /**
   * The name of the unique index a duplicate-key error violated, read from the driver's {@link
   * MongoWriteException} cause rather than the Spring exception's message: that's the server's own
   * error for this one write, with a code that says it really is a duplicate key. Empty if the
   * exception doesn't carry one, so an unrecognized duplicate key is never taken for a jersey
   * clash.
   */
  static Optional<String> violatedIndex(DuplicateKeyException e) {
    if (!(e.getCause() instanceof MongoWriteException writeException)
        || writeException.getError().getCategory() != ErrorCategory.DUPLICATE_KEY) {
      return Optional.empty();
    }
    Matcher matcher = DUPLICATE_KEY_INDEX.matcher(writeException.getError().getMessage());
    return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
  }

  private List<Player> load(PlayerStatus status) {
    return switch (status) {
      case ACTIVE -> playerRepository.findByClubIdAndActive(clubContext.requireClubId(), true);
      case RELEASED -> playerRepository.findByClubIdAndActive(clubContext.requireClubId(), false);
      case ALL -> playerRepository.findAll();
    };
  }

  private static boolean matches(PlayerFilter filter, Player player, LocalDate today) {
    if (filter.position() != null && player.getPrimaryPosition() != filter.position()) {
      return false;
    }
    if (filter.medicalStatus() != null && player.getMedicalStatus() != filter.medicalStatus()) {
      return false;
    }
    if (filter.preferredFoot() != null && player.getPreferredFoot() != filter.preferredFoot()) {
      return false;
    }
    if (filter.minAge() == null && filter.maxAge() == null) {
      return true;
    }
    if (player.getDateOfBirth() == null) {
      return false;
    }
    int age = Period.between(player.getDateOfBirth(), today).getYears();
    return (filter.minAge() == null || age >= filter.minAge())
        && (filter.maxAge() == null || age <= filter.maxAge());
  }

  private static RuntimeException translateDuplicateKey(DuplicateKeyException e, Player player) {
    if (player.getJerseyNumber() != null
        && violatedIndex(e).filter(Player.JERSEY_NUMBER_INDEX::equals).isPresent()) {
      return new JerseyNumberTakenException(player.getJerseyNumber());
    }
    return e;
  }

  private static NotFoundException notFound() {
    return new NotFoundException("Player not found");
  }
}
