package com.squadpulse.auth;

import com.squadpulse.auth.JwtService.AccessToken;
import com.squadpulse.auth.RefreshTokenService.RefreshSession;
import com.squadpulse.auth.RefreshTokenService.Rotation;
import com.squadpulse.common.ClubContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Login, refresh and logout (see docs/spec.md section 10): turns credentials or a refresh token
 * into an access token (see {@link JwtService}) plus a refresh token (see {@link
 * RefreshTokenService}).
 */
@Service
class AuthService {

  /**
   * A freshly issued pair: the access token goes in the response body, the refresh token in a
   * cookie.
   */
  record IssuedTokens(AccessToken accessToken, String refreshToken) {}

  private final UserRepository userRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtService jwtService;
  private final RefreshTokenService refreshTokenService;
  private final ClubContext clubContext;

  /**
   * Checked instead of a real hash when there's none to check (unknown email, or an invited user
   * who hasn't set a password yet), so those cases cost the same Argon2 run as a wrong password and
   * response times don't reveal whether an email is registered.
   */
  private final String dummyPasswordHash;

  AuthService(
      UserRepository userRepository,
      PasswordEncoder passwordEncoder,
      JwtService jwtService,
      RefreshTokenService refreshTokenService,
      ClubContext clubContext) {
    this.userRepository = userRepository;
    this.passwordEncoder = passwordEncoder;
    this.jwtService = jwtService;
    this.refreshTokenService = refreshTokenService;
    this.clubContext = clubContext;
    this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
  }

  /**
   * @throws InvalidCredentialsException if the email is unknown, the password is wrong, the user
   *     has no password yet, or the user is deactivated — indistinguishably
   */
  IssuedTokens login(String email, String password) {
    // Login runs before any club context exists, hence the @GloballyScoped lookup.
    Optional<User> user = userRepository.findByEmail(User.normalizeEmail(email));
    String passwordHash = user.map(User::getPasswordHash).orElse(null);
    boolean passwordMatches =
        passwordEncoder.matches(password, passwordHash != null ? passwordHash : dummyPasswordHash);

    if (user.isEmpty() || passwordHash == null || !passwordMatches || !user.get().isActive()) {
      throw new InvalidCredentialsException();
    }
    return new IssuedTokens(
        jwtService.issue(user.get()),
        refreshTokenService.issue(user.get().getId(), user.get().getClubId()));
  }

  /**
   * Rotates the refresh token and issues a new access token from the user's <b>current</b> record,
   * so a changed permission level shows up at the next refresh.
   *
   * @throws InvalidRefreshTokenException if the refresh token isn't valid (see {@link
   *     RefreshTokenService#rotate(String)}), or its user no longer exists, has been deactivated or
   *     has no password — the family is then revoked, ending that session for good
   */
  IssuedTokens refresh(String refreshToken) {
    Rotation rotation = refreshTokenService.rotate(refreshToken);
    Optional<User> user = findUser(rotation.session());
    if (user.isEmpty() || !user.get().isActive() || user.get().getPasswordHash() == null) {
      refreshTokenService.revoke(rotation.refreshToken());
      throw new InvalidRefreshTokenException();
    }
    return new IssuedTokens(jwtService.issue(user.get()), rotation.refreshToken());
  }

  /** Ends the refresh token's session (its family). Idempotent; other sessions are unaffected. */
  void logout(String refreshToken) {
    refreshTokenService.revoke(refreshToken);
  }

  /**
   * Looks the user up in the session's club. /auth/refresh is public, so the request may have no
   * club context — or, with a stray access token attached, another one — hence set here and the
   * previous value restored afterwards.
   */
  private Optional<User> findUser(RefreshSession session) {
    Optional<String> previousClubId = clubContext.getClubId();
    clubContext.setClubId(session.clubId());
    try {
      return userRepository.findById(session.userId());
    } finally {
      previousClubId.ifPresentOrElse(clubContext::setClubId, clubContext::clear);
    }
  }
}
