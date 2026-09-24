package com.squadpulse.common;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/** Pins the validation clock so the exact 18th/100th-birthday boundaries are deterministic. */
class AdultAgeValidatorTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
  private static final Clock FIXED_CLOCK =
      Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  private static final ValidatorFactory VALIDATOR_FACTORY =
      Validation.byDefaultProvider()
          .configure()
          .clockProvider(() -> FIXED_CLOCK)
          .buildValidatorFactory();
  private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

  record Person(@AdultAge LocalDate dateOfBirth) {}

  @AfterAll
  static void closeValidatorFactory() {
    VALIDATOR_FACTORY.close();
  }

  @Test
  void acceptsSomeoneWhoTurns18Today() {
    assertThat(isValid(TODAY.minusYears(18))).isTrue();
  }

  @Test
  void rejectsSomeoneWhoTurns18Tomorrow() {
    assertThat(isValid(TODAY.minusYears(18).plusDays(1))).isFalse();
  }

  @Test
  void acceptsSomeoneWhoTurns100Tomorrow() {
    assertThat(isValid(TODAY.minusYears(100).plusDays(1))).isTrue();
  }

  @Test
  void rejectsSomeoneWhoTurns100Today() {
    assertThat(isValid(TODAY.minusYears(100))).isFalse();
  }

  @Test
  void rejectsADateOfBirthInTheFuture() {
    assertThat(isValid(TODAY.plusDays(1))).isFalse();
  }

  @Test
  void treatsNullAsValidLeavingRequirednessToNotNull() {
    assertThat(isValid(null)).isTrue();
  }

  private static boolean isValid(LocalDate dateOfBirth) {
    return VALIDATOR.validate(new Person(dateOfBirth)).isEmpty();
  }
}
