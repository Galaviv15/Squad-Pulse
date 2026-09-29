package com.squadpulse.squad;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validates {@link DistinctPositions}. */
public class DistinctPositionsValidator
    implements ConstraintValidator<DistinctPositions, HasPositions> {

  static final String SECONDARY_POSITION_PROPERTY = "secondaryPosition";

  @Override
  public boolean isValid(HasPositions value, ConstraintValidatorContext context) {
    if (value == null
        || value.secondaryPosition() == null
        || value.secondaryPosition() != value.primaryPosition()) {
      return true;
    }
    context.disableDefaultConstraintViolation();
    context
        .buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
        .addPropertyNode(SECONDARY_POSITION_PROPERTY)
        .addConstraintViolation();
    return false;
  }
}
