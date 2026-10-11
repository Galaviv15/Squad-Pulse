package com.squadpulse.auth;

import com.squadpulse.common.ClubContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.bson.types.ObjectId;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The system owner's club operations (see docs/spec.md section 09): creating a new {@link Club}
 * together with its initial Club Manager {@link User}, optionally with its football.org.il mapping,
 * and replacing an existing club's mapping (KAN-65). Its only caller is {@link
 * ClubBootstrapRunner}; nothing here is reachable over the network.
 *
 * <p>Gated by the owner secret ({@code OWNER_BOOTSTRAP_SECRET}, see {@link
 * OwnerBootstrapProperties}), not by RBAC: no user exists for the new club yet. The secret is
 * checked, and all input validated, before anything touches the database. The two inserts then run
 * in one MongoDB transaction, so they both succeed or neither does.
 *
 * <p>Like the secret itself, this only exists under the {@value ClubBootstrapRunner#PROFILE}
 * profile.
 */
@Service
@Profile(ClubBootstrapRunner.PROFILE)
class ClubBootstrapService {

  /**
   * What the owner supplies. {@code managerDateOfBirth} is optional, like on {@link User}, and so
   * is {@code footballOrgIl} ({@code null}: the club isn't mapped).
   */
  record NewClub(
      String clubName,
      String managerEmail,
      String managerPassword,
      String managerFullName,
      LocalDate managerDateOfBirth,
      FootballOrgIlMapping footballOrgIl) {

    /** A club without a football.org.il mapping. */
    NewClub(
        String clubName,
        String managerEmail,
        String managerPassword,
        String managerFullName,
        LocalDate managerDateOfBirth) {
      this(clubName, managerEmail, managerPassword, managerFullName, managerDateOfBirth, null);
    }
  }

  record CreatedClub(Club club, User manager) {}

  /** The club's mapping before the update ({@code null}: none) and the club as stored after it. */
  record UpdatedMapping(FootballOrgIlMapping previous, Club club) {}

  private final ClubRepository clubRepository;
  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final Validator validator;
  private final ClubContext clubContext;
  private final TransactionTemplate transactionTemplate;
  private final byte[] ownerSecret;

  ClubBootstrapService(
      ClubRepository clubRepository,
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      Validator validator,
      ClubContext clubContext,
      PlatformTransactionManager transactionManager,
      OwnerBootstrapProperties ownerBootstrapProperties) {
    this.clubRepository = clubRepository;
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.validator = validator;
    this.clubContext = clubContext;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.ownerSecret = ownerBootstrapProperties.ownerSecret().getBytes(StandardCharsets.UTF_8);
  }

  /**
   * @throws ClubBootstrapException if {@code candidate} (what the owner typed at the prompt) is
   *     missing or doesn't match {@code OWNER_BOOTSTRAP_SECRET}
   */
  void verifyOwnerSecret(String candidate) {
    // Constant-time comparison, so the check doesn't reveal how much of a guess was right.
    if (candidate == null
        || !MessageDigest.isEqual(ownerSecret, candidate.getBytes(StandardCharsets.UTF_8))) {
      throw new ClubBootstrapException("missing or wrong owner secret");
    }
  }

  /**
   * Creates the club and its Club Manager ({@link Title#CLUB_MANAGER} / {@link
   * PermissionLevel#ADMIN}) atomically.
   *
   * @throws ClubBootstrapException if the owner secret is wrong or the input is invalid — nothing
   *     is written
   * @throws org.springframework.dao.DuplicateKeyException if the email is already taken — the
   *     transaction is rolled back, so the club isn't created either
   */
  CreatedClub bootstrap(String ownerSecret, NewClub newClub) {
    verifyOwnerSecret(ownerSecret);

    Club club = new Club();
    club.setName(newClub.clubName());
    club.setFootballOrgIl(newClub.footballOrgIl());

    User manager = new User();
    manager.setEmail(newClub.managerEmail());
    manager.setTitle(Title.CLUB_MANAGER);
    manager.setPermissionLevel(PermissionLevel.ADMIN);
    manager.setFullName(newClub.managerFullName());
    manager.setDateOfBirth(newClub.managerDateOfBirth());

    List<String> problems = new ArrayList<>();
    violations(club, "club ", problems);
    violations(manager, "manager ", problems);
    if (newClub.managerPassword() == null || newClub.managerPassword().isBlank()) {
      problems.add("manager password must not be blank");
    }
    if (!problems.isEmpty()) {
      throw new ClubBootstrapException(String.join("; ", problems));
    }

    // Hashed only after validation passes — Argon2id is deliberately expensive.
    manager.setPasswordHash(passwordEncoder.encode(newClub.managerPassword()));

    return transactionTemplate.execute(
        status -> {
          Club savedClub = clubRepository.insert(club);
          // UserRepository is club-scoped: the new club's id becomes the manager's clubId.
          clubContext.setClubId(savedClub.getId());
          try {
            return new CreatedClub(savedClub, userRepository.insert(manager));
          } finally {
            clubContext.clear();
          }
        });
  }

  /**
   * Replaces the football.org.il mapping of the existing club {@code clubId} — all three ids
   * together, by a targeted update of that field alone ({@link
   * ClubRepository#updateFootballOrgIlByClubId}); nothing else on the club changes.
   *
   * @throws ClubBootstrapException if the owner secret is wrong (checked first, so the club isn't
   *     even looked up), {@code mapping} is missing or invalid, {@code clubId} isn't a club id, or
   *     no such club exists — nothing is written
   */
  UpdatedMapping updateFootballOrgIl(
      String ownerSecret, String clubId, FootballOrgIlMapping mapping) {
    verifyOwnerSecret(ownerSecret);

    if (mapping == null) {
      throw new ClubBootstrapException("the football.org.il mapping is required");
    }
    validateFootballOrgIl(mapping);
    // Clubs get ObjectId ids at creation. Anything else can't name one; said explicitly rather
    // than as "not found", so a typo in the id is obvious.
    if (clubId == null || !ObjectId.isValid(clubId)) {
      throw new ClubBootstrapException("the club id must be 24 hexadecimal characters");
    }

    // Checked first: the update can't tell a missing club from an unchanged mapping (see
    // ClubRepository#updateFootballOrgIlByClubId). Clubs are never deleted, so the club can't
    // disappear between this read, the update and the re-read below.
    Club before =
        clubRepository
            .findById(clubId)
            .orElseThrow(() -> new ClubBootstrapException("no club with id " + clubId));
    clubRepository.updateFootballOrgIlByClubId(clubId, mapping);
    Club after =
        clubRepository
            .findById(clubId)
            .orElseThrow(() -> new IllegalStateException("Club " + clubId + " disappeared"));
    return new UpdatedMapping(before.getFootballOrgIl(), after);
  }

  /**
   * Checks {@code mapping} against its own constraints, with the same messages as {@link
   * #bootstrap}'s check of it through {@link Club}'s {@code @Valid} field — so the runner can
   * refuse an invalid mapping before prompting for the Club Manager password, and {@link
   * #updateFootballOrgIl} shares the one source of the messages. {@link #bootstrap} still validates
   * the club itself, for any other caller.
   *
   * @throws ClubBootstrapException listing every violation
   */
  void validateFootballOrgIl(FootballOrgIlMapping mapping) {
    List<String> problems = new ArrayList<>();
    violations(mapping, "club footballOrgIl.", problems);
    if (!problems.isEmpty()) {
      throw new ClubBootstrapException(String.join("; ", problems));
    }
  }

  /** Field and message only — never the rejected value, so nothing sensitive can leak into logs. */
  private <T> void violations(T bean, String prefix, List<String> problems) {
    for (ConstraintViolation<T> violation : validator.validate(bean)) {
      problems.add(prefix + violation.getPropertyPath() + " " + violation.getMessage());
    }
  }
}
