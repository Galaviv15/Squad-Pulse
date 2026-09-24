package com.squadpulse.auth;

import com.squadpulse.common.ClubContext;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates a new {@link Club} together with its initial Club Manager {@link User} — the system
 * owner's onboarding step (see docs/spec.md section 09). Its only caller is {@link
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

  /** What the owner supplies. {@code managerDateOfBirth} is optional, like on {@link User}. */
  record NewClub(
      String clubName,
      String managerEmail,
      String managerPassword,
      String managerFullName,
      LocalDate managerDateOfBirth) {}

  record CreatedClub(Club club, User manager) {}

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

  /** Field and message only — never the rejected value, so nothing sensitive can leak into logs. */
  private <T> void violations(T bean, String prefix, List<String> problems) {
    for (ConstraintViolation<T> violation : validator.validate(bean)) {
      problems.add(prefix + violation.getPropertyPath() + " " + violation.getMessage());
    }
  }
}
