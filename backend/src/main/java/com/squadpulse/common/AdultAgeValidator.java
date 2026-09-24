package com.squadpulse.common;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.LocalDate;
import java.time.Period;

/**
 * Validates {@link AdultAge}. Reads "today" from the validation context's {@link
 * jakarta.validation.ClockProvider} rather than {@link LocalDate#now()}, so tests can pin the clock
 * and check the exact birthday boundaries deterministically.
 */
public class AdultAgeValidator implements ConstraintValidator<AdultAge, LocalDate> {

  static final int MIN_AGE = 18;
  static final int MAX_AGE = 99;

  @Override
  public boolean isValid(LocalDate dateOfBirth, ConstraintValidatorContext context) {
    if (dateOfBirth == null) {
      return true;
    }
    LocalDate today = LocalDate.now(context.getClockProvider().getClock());
    if (dateOfBirth.isAfter(today)) {
      return false;
    }
    int age = Period.between(dateOfBirth, today).getYears();
    return age >= MIN_AGE && age <= MAX_AGE;
  }
}
