package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

class ConflictExceptionTest {

  @ParameterizedTest
  @ValueSource(strings = {"A", "STALE_VERSION", "CODE_2", "X1"})
  void acceptsAnUpperSnakeCaseCode(String code) {
    assertThat(new ConflictException(code, "message").getCode()).isEqualTo(code);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "stale_version",
        "Stale_Version",
        "_STALE",
        "1STALE",
        "STALE-VERSION",
        "STALE "
      })
  void rejectsAMalformedCode(String code) {
    assertThatThrownBy(() -> new ConflictException(code, "message"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsANullCode() {
    assertThatNullPointerException().isThrownBy(() -> new ConflictException(null, "message"));
  }

  @Test
  void theHandlerSendsTheCodeAndTheMessage() {
    ApiErrorResponse body =
        new GlobalExceptionHandler()
            .handleConflict(new ConflictException("SOME_CONFLICT", "It clashes"))
            .getBody();

    assertThat(body.status()).isEqualTo(HttpStatus.CONFLICT.value());
    assertThat(body.error()).isEqualTo("Conflict");
    assertThat(body.code()).isEqualTo("SOME_CONFLICT");
    assertThat(body.message()).isEqualTo("It clashes");
    assertThat(body.details()).isEmpty();
  }
}
