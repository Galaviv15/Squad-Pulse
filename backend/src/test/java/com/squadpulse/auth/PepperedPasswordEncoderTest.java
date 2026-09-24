package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Argon2id + pepper hashing (KAN-18). Dummy peppers only — not real secrets. */
class PepperedPasswordEncoderTest {

  private static final String PEPPER_A = "test-only-pepper-a-not-a-real-secret";
  private static final String PEPPER_B = "test-only-pepper-b-not-a-real-secret";
  private static final String PASSWORD = "correct horse battery staple";

  private final PepperedPasswordEncoder encoder = new PepperedPasswordEncoder(PEPPER_A);

  @Test
  void hashThenVerifyRoundTrips() {
    String hash = encoder.encode(PASSWORD);

    assertThat(encoder.matches(PASSWORD, hash)).isTrue();
    assertThat(encoder.matches("wrong password", hash)).isFalse();
  }

  @Test
  void producesArgon2idHashWithoutTheRawPassword() {
    String hash = encoder.encode(PASSWORD);

    assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD).doesNotContain(PEPPER_A);
  }

  @Test
  void hashingTheSamePasswordTwiceUsesDifferentSalts() {
    assertThat(encoder.encode(PASSWORD)).isNotEqualTo(encoder.encode(PASSWORD));
  }

  @Test
  void pepperAffectsTheDigest() {
    PepperedPasswordEncoder otherPepper = new PepperedPasswordEncoder(PEPPER_B);

    String hashA = encoder.encode(PASSWORD);
    String hashB = otherPepper.encode(PASSWORD);

    // Salts differ anyway, so the stronger check is cross-verification: a hash made with one
    // pepper must not verify under the other.
    assertThat(hashA).isNotEqualTo(hashB);
    assertThat(otherPepper.matches(PASSWORD, hashA)).isFalse();
    assertThat(encoder.matches(PASSWORD, hashB)).isFalse();
  }

  @Test
  void rejectsMissingOrShortPepper() {
    assertThatThrownBy(() -> new PepperedPasswordEncoder(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new PepperedPasswordEncoder("too-short"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("too-short");
  }
}
