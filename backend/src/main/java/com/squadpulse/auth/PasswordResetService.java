package com.squadpulse.auth;

import com.squadpulse.common.ClubContext;
import com.squadpulse.common.EmailSender;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Forgot / reset password (see docs/spec.md section 10): an emailed one-time code (see {@link
 * PasswordResetCodeService}) lets a user set a new password. The same reset step is an invited
 * user's activation — setting their first password.
 *
 * <p>Neither step may reveal whether an email is registered: forgot-password always ends the same
 * way (see {@link #requestReset}), and every reset failure is the same {@link
 * InvalidResetCodeException}.
 *
 * <p>Both run without a club context (the endpoints are public). Looking the user up works anyway —
 * {@link UserRepository#findByEmail} is global — but saving goes through the club-scoped
 * repository, so it runs in the <b>found user's</b> club (see {@link ClubContext#callAs}), never
 * one taken from the request.
 *
 * <p>The emails are in English for now; a Hebrew template belongs with the real email provider and
 * i18n (Phase 6+).
 */
@Service
class PasswordResetService {

  static final String RESET_SUBJECT = "Your SquadPulse password reset code";
  static final String ACTIVATION_SUBJECT = "Activate your SquadPulse account";

  private final UserRepository userRepository;
  private final PasswordResetCodeService codeService;
  private final PasswordResetProperties properties;
  private final PasswordEncoder passwordEncoder;
  private final EmailSender emailSender;
  private final ClubContext clubContext;
  private final Clock clock;

  @Autowired
  PasswordResetService(
      UserRepository userRepository,
      PasswordResetCodeService codeService,
      PasswordResetProperties properties,
      PasswordEncoder passwordEncoder,
      EmailSender emailSender,
      ClubContext clubContext) {
    this(
        userRepository,
        codeService,
        properties,
        passwordEncoder,
        emailSender,
        clubContext,
        Clock.systemUTC());
  }

  /** For tests: {@code clock} decides a reset's {@code sessionsInvalidatedAt}. */
  PasswordResetService(
      UserRepository userRepository,
      PasswordResetCodeService codeService,
      PasswordResetProperties properties,
      PasswordEncoder passwordEncoder,
      EmailSender emailSender,
      ClubContext clubContext,
      Clock clock) {
    this.userRepository = userRepository;
    this.codeService = codeService;
    this.properties = properties;
    this.passwordEncoder = passwordEncoder;
    this.emailSender = emailSender;
    this.clubContext = clubContext;
    this.clock = clock;
  }

  /**
   * Emails a reset code to the user with this email — if there is one, it's active, and the email
   * is within its request limit. Otherwise silently does nothing: the caller answers identically
   * either way, so this never reveals whether an email is registered, deactivated or throttled.
   *
   * <p>The request is counted first, for unknown emails exactly like registered ones. A deactivated
   * user gets no code: their Club Manager cut their access, and a reset mustn't undo that.
   *
   * <p>What does differ is a few milliseconds of HMAC, Redis and email work, done only for a real
   * user — acceptable while {@link EmailSender} is the fast, synchronous logging stub, and why a
   * real one must send asynchronously.
   */
  void requestReset(String email) {
    String normalizedEmail = User.normalizeEmail(email);
    if (!codeService.recordRequest(normalizedEmail)) {
      return;
    }
    // No club context on a public endpoint, hence the @GloballyScoped lookup.
    Optional<User> user = userRepository.findByEmail(normalizedEmail);
    if (user.isEmpty() || !user.get().isActive()) {
      return;
    }
    String code = codeService.issue(normalizedEmail);
    emailSender.send(
        normalizedEmail,
        RESET_SUBJECT,
        """
        Your SquadPulse password reset code is: %s

        It expires in %d minutes. If you didn't ask to reset your password, ignore this email.
        """
            .formatted(code, properties.codeTtl().toMinutes()));
  }

  /**
   * Emails a freshly invited user their activation code — the same kind of code, with the same TTL,
   * that {@link #resetPassword} accepts. Not counted against the email's request limit: an invite
   * is an authenticated {@code ADMIN} action, not a public request. If the code expires unused, the
   * user asks for a new one through forgot-password.
   */
  void sendActivationCode(User invited) {
    String code = codeService.issue(invited.getEmail());
    emailSender.send(
        invited.getEmail(),
        ACTIVATION_SUBJECT,
        """
        You've been invited to SquadPulse. Your activation code is: %s

        Enter it together with a password of your choice within %d minutes.
        If it expires, use "Forgot password" to get a new one.
        """
            .formatted(code, properties.codeTtl().toMinutes()));
  }

  /**
   * Sets a new password with an emailed code — consuming the code — and ends every refresh session
   * the user has (see {@link User#getSessionsInvalidatedAt()}). Access tokens already issued stay
   * valid until they expire. Doesn't log the user in.
   *
   * <p>This is also an invited user's activation: their {@code passwordHash} goes from {@code null}
   * to set, with nothing special about it.
   *
   * <p><b>Concurrent writes</b> (KAN-24). The code is already consumed when the user is saved, so a
   * save that loses an optimistic-locking race (e.g. to an admin changing the user's permission
   * level meanwhile) is retried rather than failed: the user is reloaded and the change re-applied,
   * so both writes survive. The Argon2 hash and the invalidation instant are computed once, before
   * that, and reused. Each attempt <b>re-checks</b> that the user still exists and is active —
   * that's the security point: if the user was deactivated in between, the reset fails instead of
   * writing its stale copy back over the deactivation. If every attempt conflicts, the last {@link
   * OptimisticLockingFailureException} propagates (a 409); this never returns normally unless the
   * save succeeded.
   *
   * @throws InvalidResetCodeException if the code isn't the email's current one, or its user no
   *     longer exists or has been deactivated — indistinguishably. The code is consumed by then.
   * @throws OptimisticLockingFailureException if all {@link UserWriteRetry#MAX_ATTEMPTS} attempts
   *     lost a race
   */
  void resetPassword(String email, String code, String newPassword) {
    String normalizedEmail = User.normalizeEmail(email);
    if (!codeService.verify(normalizedEmail, code)) {
      throw new InvalidResetCodeException();
    }
    String passwordHash = passwordEncoder.encode(newPassword);
    Instant sessionsInvalidatedAt = clock.instant();
    UserWriteRetry.withRetry(
        () -> {
          User user =
              userRepository
                  .findByEmail(normalizedEmail)
                  .filter(User::isActive)
                  .orElseThrow(InvalidResetCodeException::new);
          user.setPasswordHash(passwordHash);
          user.setSessionsInvalidatedAt(sessionsInvalidatedAt);
          return clubContext.callAs(user.getClubId(), () -> userRepository.save(user));
        });
  }
}
