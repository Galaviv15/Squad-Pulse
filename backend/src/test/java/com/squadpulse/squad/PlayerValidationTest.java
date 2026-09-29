package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for the Bean Validation constraints on {@link Player}. The exact age boundaries of
 * {@code @AdultAge} are covered by {@code AdultAgeValidatorTest}; here it's enough to prove the
 * constraint is wired onto {@code dateOfBirth}.
 */
class PlayerValidationTest {

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
  void aFullyPopulatedPlayerIsValid() {
    assertThat(VALIDATOR.validate(fullPlayer())).isEmpty();
  }

  @Test
  void aPlayerWithOnlyTheRequiredFieldsIsValid() {
    Player player = new Player();
    player.setFullName("Yossi Benayoun");
    player.setPrimaryPosition(Position.AM);
    player.setDateOfBirth(TODAY.minusYears(25));

    assertThat(VALIDATOR.validate(player)).isEmpty();
  }

  @Test
  void defaultsToFitAndActive() {
    Player player = new Player();

    assertThat(player.getMedicalStatus()).isEqualTo(MedicalStatus.FIT);
    assertThat(player.isActive()).isTrue();
  }

  // --- fullName ----------------------------------------------------------------------------------

  @Test
  void rejectsANullFullName() {
    Player player = fullPlayer();
    player.setFullName(null);

    assertThat(violatedFields(player)).containsExactly("fullName");
  }

  @Test
  void rejectsABlankFullName() {
    Player player = fullPlayer();
    player.setFullName("   ");

    assertThat(violatedFields(player)).containsExactly("fullName");
  }

  @Test
  void acceptsAFullNameOf100Characters() {
    Player player = fullPlayer();
    player.setFullName("a".repeat(100));

    assertThat(violatedFields(player)).isEmpty();
  }

  @Test
  void rejectsAFullNameOf101Characters() {
    Player player = fullPlayer();
    player.setFullName("a".repeat(101));

    assertThat(violatedFields(player)).containsExactly("fullName");
  }

  /** Surrounding whitespace doesn't count towards the limit: it's trimmed before validation. */
  @Test
  void fullNameIsTrimmed() {
    Player player = fullPlayer();
    player.setFullName("  " + "a".repeat(100) + " \t");

    assertThat(player.getFullName()).isEqualTo("a".repeat(100));
    assertThat(violatedFields(player)).isEmpty();
  }

  // --- numeric ranges ----------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(ints = {1, 99})
  void acceptsAJerseyNumberAtTheBoundary(int jerseyNumber) {
    assertThat(violatedFieldsWith(Player::setJerseyNumber, jerseyNumber)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 100})
  void rejectsAJerseyNumberJustOutsideTheRange(int jerseyNumber) {
    assertThat(violatedFieldsWith(Player::setJerseyNumber, jerseyNumber))
        .containsExactly("jerseyNumber");
  }

  @ParameterizedTest
  @ValueSource(ints = {140, 220})
  void acceptsAHeightAtTheBoundary(int heightCm) {
    assertThat(violatedFieldsWith(Player::setHeightCm, heightCm)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {139, 221})
  void rejectsAHeightJustOutsideTheRange(int heightCm) {
    assertThat(violatedFieldsWith(Player::setHeightCm, heightCm)).containsExactly("heightCm");
  }

  @ParameterizedTest
  @ValueSource(ints = {40, 150})
  void acceptsAWeightAtTheBoundary(int weightKg) {
    assertThat(violatedFieldsWith(Player::setWeightKg, weightKg)).isEmpty();
  }

  @ParameterizedTest
  @ValueSource(ints = {39, 151})
  void rejectsAWeightJustOutsideTheRange(int weightKg) {
    assertThat(violatedFieldsWith(Player::setWeightKg, weightKg)).containsExactly("weightKg");
  }

  // --- dateOfBirth -------------------------------------------------------------------------------

  @Test
  void rejectsANullDateOfBirth() {
    Player player = fullPlayer();
    player.setDateOfBirth(null);

    assertThat(violatedFields(player)).containsExactly("dateOfBirth");
  }

  @Test
  void rejectsADateOfBirthUnder18() {
    Player player = fullPlayer();
    player.setDateOfBirth(TODAY.minusYears(18).plusDays(1));

    assertThat(violatedFields(player)).containsExactly("dateOfBirth");
  }

  @Test
  void rejectsADateOfBirthOver99() {
    Player player = fullPlayer();
    player.setDateOfBirth(TODAY.minusYears(100));

    assertThat(violatedFields(player)).containsExactly("dateOfBirth");
  }

  // --- required enums ----------------------------------------------------------------------------

  @Test
  void rejectsANullPrimaryPosition() {
    Player player = fullPlayer();
    player.setPrimaryPosition(null);

    assertThat(violatedFields(player)).containsExactly("primaryPosition");
  }

  @Test
  void rejectsANullMedicalStatus() {
    Player player = fullPlayer();
    player.setMedicalStatus(null);

    assertThat(violatedFields(player)).containsExactly("medicalStatus");
  }

  // --- secondary position ------------------------------------------------------------------------

  @Test
  void rejectsASecondaryPositionEqualToThePrimary() {
    Player player = fullPlayer();
    player.setPrimaryPosition(Position.CB);
    player.setSecondaryPosition(Position.CB);

    Set<ConstraintViolation<Player>> violations = VALIDATOR.validate(player);

    assertThat(violations)
        .singleElement()
        .satisfies(
            violation -> {
              assertThat(violation.getPropertyPath()).hasToString("secondaryPosition");
              assertThat(violation.getMessage())
                  .isEqualTo("secondary position must differ from the primary position");
            });
  }

  @Test
  void acceptsASecondaryPositionDifferentFromThePrimary() {
    Player player = fullPlayer();
    player.setPrimaryPosition(Position.CB);
    player.setSecondaryPosition(Position.DM);

    assertThat(violatedFields(player)).isEmpty();
  }

  @Test
  void acceptsNoSecondaryPosition() {
    Player player = fullPlayer();
    player.setSecondaryPosition(null);

    assertThat(violatedFields(player)).isEmpty();
  }

  /** Only {@code @NotNull} complains about a missing primary position, not the distinct rule. */
  @Test
  void aMissingPrimaryPositionIsOnlyReportedOnce() {
    Player player = fullPlayer();
    player.setPrimaryPosition(null);
    player.setSecondaryPosition(null);

    assertThat(violatedFields(player)).containsExactly("primaryPosition");
  }

  // --- helpers -----------------------------------------------------------------------------------

  private static Player fullPlayer() {
    Player player = new Player();
    player.setFullName("Eran Zahavi");
    player.setPrimaryPosition(Position.ST);
    player.setSecondaryPosition(Position.AM);
    player.setJerseyNumber(7);
    player.setDateOfBirth(TODAY.minusYears(30));
    player.setHeightCm(180);
    player.setWeightKg(75);
    player.setPreferredFoot(PreferredFoot.RIGHT);
    player.setMedicalStatus(MedicalStatus.INJURED);
    player.setActive(true);
    return player;
  }

  private static Set<String> violatedFieldsWith(BiConsumer<Player, Integer> setter, int value) {
    Player player = fullPlayer();
    setter.accept(player, value);
    return violatedFields(player);
  }

  private static Set<String> violatedFields(Player player) {
    return VALIDATOR.validate(player).stream()
        .map(violation -> violation.getPropertyPath().toString())
        .collect(Collectors.toSet());
  }
}
