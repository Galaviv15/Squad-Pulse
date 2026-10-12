package com.squadpulse.common;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The string contains no ISO control character ({@link Character#isISOControl}: U+0000–U+001F and
 * U+007F–U+009F — tabs and line breaks included). {@code null} is valid; combine with
 * {@code @NotNull} / {@code @NotBlank} where a value is required.
 *
 * <p>For text that is data, never layout: a team name, a venue. A control character there has no
 * meaning and can break whatever is built from the text — e.g. a key joined with one (KAN-66).
 */
@Documented
@Constraint(validatedBy = NoControlCharactersValidator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface NoControlCharacters {

  String message() default "must not contain control characters";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
