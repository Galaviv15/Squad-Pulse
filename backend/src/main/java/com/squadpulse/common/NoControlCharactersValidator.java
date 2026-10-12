package com.squadpulse.common;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Validates {@link NoControlCharacters}. */
public class NoControlCharactersValidator
    implements ConstraintValidator<NoControlCharacters, String> {

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || value.chars().noneMatch(Character::isISOControl);
  }
}
