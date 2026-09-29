package com.squadpulse.squad;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A player's secondary position, if set, must differ from their primary position. Goes on a type
 * that implements {@link HasPositions}.
 *
 * <p>Valid when either position is {@code null} — requiring the primary position is {@code
 * NotNull}'s job, not this constraint's. The violation is reported on the {@code secondaryPosition}
 * property rather than on the object, so it surfaces as a field error in a 400 response like any
 * other invalid field.
 */
@Documented
@Constraint(validatedBy = DistinctPositionsValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface DistinctPositions {

  String message() default "secondary position must differ from the primary position";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
