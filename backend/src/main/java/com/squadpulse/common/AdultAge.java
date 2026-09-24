package com.squadpulse.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The annotated {@link java.time.LocalDate} date of birth must correspond to an age between {@value
 * AdultAgeValidator#MIN_AGE} and {@value AdultAgeValidator#MAX_AGE} (inclusive) today — SquadPulse
 * serves adult clubs only (see docs/spec.md section 05). Shared by every entity with a date of
 * birth (User, and later Player) so they all apply the exact same rule.
 *
 * <p>A {@code null} value is considered valid, per the Bean Validation convention; combine with
 * {@code @NotNull} if the field is required.
 */
@Documented
@Constraint(validatedBy = AdultAgeValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface AdultAge {

  String message() default "must correspond to an age between 18 and 99";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
