package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link DistinctPositions} works on any {@link HasPositions}, not just {@link Player} —
 * here a record shaped like the player request DTOs will be.
 */
class DistinctPositionsValidatorTest {

  private static final ValidatorFactory VALIDATOR_FACTORY =
      Validation.buildDefaultValidatorFactory();
  private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

  @DistinctPositions
  record PositionsRequest(Position primaryPosition, Position secondaryPosition)
      implements HasPositions {}

  @AfterAll
  static void closeValidatorFactory() {
    VALIDATOR_FACTORY.close();
  }

  @Test
  void rejectsTheSamePositionTwiceOnTheSecondaryPosition() {
    Set<ConstraintViolation<PositionsRequest>> violations =
        VALIDATOR.validate(new PositionsRequest(Position.GK, Position.GK));

    assertThat(violations)
        .singleElement()
        .satisfies(
            violation -> assertThat(violation.getPropertyPath()).hasToString("secondaryPosition"));
  }

  @Test
  void acceptsTwoDifferentPositions() {
    assertThat(VALIDATOR.validate(new PositionsRequest(Position.GK, Position.CB))).isEmpty();
  }

  @Test
  void acceptsNoSecondaryPosition() {
    assertThat(VALIDATOR.validate(new PositionsRequest(Position.GK, null))).isEmpty();
  }

  @Test
  void acceptsNoPositionsAtAll() {
    assertThat(VALIDATOR.validate(new PositionsRequest(null, null))).isEmpty();
  }
}
