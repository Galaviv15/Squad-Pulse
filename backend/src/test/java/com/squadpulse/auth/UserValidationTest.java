package com.squadpulse.auth;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Bean Validation constraints on {@link User}. The exact age boundaries of
 * {@code @AdultAge} are covered by {@code AdultAgeValidatorTest}; here it's enough to prove the
 * constraint is wired onto {@code dateOfBirth}.
 */
class UserValidationTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
  private static final Clock FIXED_CLOCK =
      Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  private static final ValidatorFactory VALIDATOR_FACTORY =
      Validation.byDefaultProvider()
          .configure()
          .clockProvider(() -> FIXED_CLOCK)
          .buildValidatorFactory();
  private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

  @AfterAll
  static void closeValidatorFactory() {
    VALIDATOR_FACTORY.close();
  }

  @Test
  void aFullyPopulatedUserIsValid() {
    assertThat(VALIDATOR.validate(validUser())).isEmpty();
  }

  @Test
  void rejectsAMalformedEmail() {
    User user = validUser();
    user.setEmail("not-an-email");

    assertThat(violatedFields(user)).containsExactly("email");
  }

  @Test
  void rejectsABlankEmail() {
    User user = validUser();
    user.setEmail("   ");

    assertThat(violatedFields(user)).containsExactly("email");
  }

  @Test
  void rejectsANullEmail() {
    User user = validUser();
    user.setEmail(null);

    assertThat(violatedFields(user)).containsExactly("email");
  }

  @Test
  void rejectsABlankFullName() {
    User user = validUser();
    user.setFullName(" ");

    assertThat(violatedFields(user)).containsExactly("fullName");
  }

  @Test
  void rejectsADateOfBirthUnder18() {
    User user = validUser();
    user.setDateOfBirth(TODAY.minusYears(17));

    assertThat(violatedFields(user)).containsExactly("dateOfBirth");
  }

  @Test
  void rejectsADateOfBirthOver99() {
    User user = validUser();
    user.setDateOfBirth(TODAY.minusYears(100));

    assertThat(violatedFields(user)).containsExactly("dateOfBirth");
  }

  @Test
  void rejectsANullTitle() {
    User user = validUser();
    user.setTitle(null);

    assertThat(violatedFields(user)).containsExactly("title");
  }

  @Test
  void rejectsANullPermissionLevel() {
    User user = validUser();
    user.setPermissionLevel(null);

    assertThat(violatedFields(user)).containsExactly("permissionLevel");
  }

  @Test
  void emailIsNormalizedToTrimmedLowerCase() {
    User user = validUser();
    user.setEmail("  Gal.Aviv@Example.COM ");

    assertThat(user.getEmail()).isEqualTo("gal.aviv@example.com");
  }

  @Test
  void isActiveByDefault() {
    assertThat(new User().isActive()).isTrue();
  }

  private static User validUser() {
    User user = new User();
    user.setEmail("coach@example.com");
    user.setPasswordHash("placeholder-hash");
    user.setTitle(Title.HEAD_COACH);
    user.setPermissionLevel(PermissionLevel.EDIT_FULL);
    user.setFullName("Dana Levi");
    user.setDateOfBirth(TODAY.minusYears(40));
    return user;
  }

  private static Set<String> violatedFields(User user) {
    Set<ConstraintViolation<User>> violations = VALIDATOR.validate(user);
    return violations.stream()
        .map(violation -> violation.getPropertyPath().toString())
        .collect(Collectors.toSet());
  }
}
