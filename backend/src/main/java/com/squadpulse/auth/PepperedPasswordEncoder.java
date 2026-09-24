package com.squadpulse.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Argon2id password hashing with a server-side pepper (see docs/spec.md section 10).
 *
 * <p>{@link Argon2PasswordEncoder} has no pepper support, so the raw password is first run through
 * HMAC-SHA256 keyed with the pepper, and only that (Base64-encoded) HMAC is handed to Argon2id.
 * HMAC rather than plain concatenation gives Argon2 a fixed-length input and keeps the pepper a
 * proper key. Without the pepper, a leaked hash can't be brute-forced, since every guess would need
 * it too.
 *
 * <p>The per-password salt is still Argon2's own (random, stored inside the encoded hash), so the
 * same password never produces the same hash twice.
 *
 * <p>Changing the pepper invalidates every stored hash — it must stay stable per environment.
 */
public class PepperedPasswordEncoder implements PasswordEncoder {

  private static final String HMAC_ALGORITHM = "HmacSHA256";
  private static final int MIN_PEPPER_LENGTH = 32;

  // OWASP Password Storage Cheat Sheet baseline for Argon2id: m=19 MiB, t=2, p=1.
  private static final int SALT_LENGTH_BYTES = 16;
  private static final int HASH_LENGTH_BYTES = 32;
  private static final int PARALLELISM = 1;
  private static final int MEMORY_KIB = 19 * 1024;
  private static final int ITERATIONS = 2;

  private final SecretKeySpec pepperKey;
  private final Argon2PasswordEncoder argon2 =
      new Argon2PasswordEncoder(
          SALT_LENGTH_BYTES, HASH_LENGTH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS);

  /**
   * @param pepper the {@code PASSWORD_PEPPER} secret. Also enforced by {@link SecurityProperties}
   *     at startup; re-checked here because this class can be constructed directly. The value is
   *     never logged or included in exception messages.
   */
  public PepperedPasswordEncoder(String pepper) {
    if (pepper == null || pepper.length() < MIN_PEPPER_LENGTH) {
      throw new IllegalArgumentException(
          "Password pepper must be at least " + MIN_PEPPER_LENGTH + " characters");
    }
    this.pepperKey = new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
  }

  @Override
  public String encode(CharSequence rawPassword) {
    return argon2.encode(pepper(rawPassword));
  }

  @Override
  public boolean matches(CharSequence rawPassword, String encodedPassword) {
    return argon2.matches(pepper(rawPassword), encodedPassword);
  }

  @Override
  public boolean upgradeEncoding(String encodedPassword) {
    return argon2.upgradeEncoding(encodedPassword);
  }

  private String pepper(CharSequence rawPassword) {
    if (rawPassword == null) {
      throw new IllegalArgumentException("Raw password must not be null");
    }
    try {
      // Mac isn't thread-safe, so one per call — negligible next to Argon2's cost.
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(pepperKey);
      byte[] digest = mac.doFinal(rawPassword.toString().getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
