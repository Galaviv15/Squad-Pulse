package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.squadpulse.common.ClubContext;
import com.squadpulse.common.ImageStorage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * {@link PlayerService#summary()} and its age arithmetic, with the repository mocked and "today"
 * pinned to {@link #TODAY}. Club scoping and which players are loaded are proven on a real MongoDB
 * in {@link PlayerApiIntegrationTest}.
 */
class PlayerServiceSummaryTest {

  private static final String CLUB = "club-a";
  private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

  private final PlayerRepository playerRepository = mock(PlayerRepository.class);
  private final ClubContext clubContext = new ClubContext();
  private final PlayerService service =
      new PlayerService(
          playerRepository,
          clubContext,
          mock(ImageStorage.class),
          Clock.fixed(TODAY.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

  @BeforeEach
  void setClub() {
    clubContext.setClubId(CLUB);
  }

  // --- exact age ---------------------------------------------------------------------------------

  static Stream<Arguments> exactAges() {
    return Stream.of(
        // A birthday today: exactly the new whole age.
        Arguments.of(LocalDate.of(2000, 10, 3), TODAY, 26.0),
        // The day before a birthday: 364 of 365 days into the year.
        Arguments.of(LocalDate.of(2000, 10, 4), TODAY, 25 + 364.0 / 365),
        // 29 February in a non-leap year: Period only counts the year complete on 1 March, so the
        // birthday year runs 2026-03-01 .. 2027-03-01 (365 days).
        Arguments.of(LocalDate.of(2000, 2, 29), LocalDate.of(2027, 2, 28), 26 + 364.0 / 365),
        Arguments.of(LocalDate.of(2000, 2, 29), LocalDate.of(2027, 3, 1), 27.0),
        // ... and in a leap year it's 29 February again: 2027-03-01 .. 2028-02-29 (365 days).
        Arguments.of(LocalDate.of(2000, 2, 29), LocalDate.of(2028, 2, 28), 27 + 364.0 / 365),
        Arguments.of(LocalDate.of(2000, 2, 29), LocalDate.of(2028, 2, 29), 28.0),
        // A birthday year that contains a 29 February is 366 days long.
        Arguments.of(LocalDate.of(1999, 3, 1), LocalDate.of(2028, 2, 29), 28 + 365.0 / 366));
  }

  @ParameterizedTest(name = "born {0}, on {1} -> {2}")
  @MethodSource("exactAges")
  void theExactAgeIsCompletedYearsPlusTheElapsedShareOfTheBirthdayYear(
      LocalDate dateOfBirth, LocalDate today, double expected) {
    BigDecimal age = PlayerService.exactAge(dateOfBirth, today);

    assertThat(age.doubleValue()).isCloseTo(expected, within(1e-12));
    assertThat(age.intValue()).isEqualTo(PlayerService.completedYears(dateOfBirth, today));
  }

  @Test
  void onALeapDayBirthdayInANonLeapYearPlusYearsWouldSay28FebruaryButPeriodSays1March() {
    LocalDate dateOfBirth = LocalDate.of(2000, 2, 29);

    assertThat(dateOfBirth.plusYears(27)).isEqualTo(LocalDate.of(2027, 2, 28));
    assertThat(PlayerService.completedYears(dateOfBirth, LocalDate.of(2027, 2, 28))).isEqualTo(26);
    assertThat(PlayerService.completedYears(dateOfBirth, LocalDate.of(2027, 3, 1))).isEqualTo(27);
  }

  // --- average and rounding ----------------------------------------------------------------------

  @Test
  void aSinglePlayersAverageIsTheirExactAgeNotTheirCompletedYears() {
    // 25 years and 300 of 365 days: exact 25.82, completed years would give 25.0.
    givenActive(player(Position.ST, LocalDate.of(2000, 12, 7)));

    assertThat(service.summary().averageAge()).isEqualByComparingTo("25.8");
  }

  @Test
  void theAverageIsOfExactAges() {
    // 25 + 300/365 = 25.822 and 27 + 183/365 = 27.501: mean 26.66 -> 26.7. Whole years: 26.0.
    givenActive(
        player(Position.ST, LocalDate.of(2000, 12, 7)),
        player(Position.CB, LocalDate.of(1999, 4, 3)));

    assertThat(service.summary().averageAge()).isEqualByComparingTo("26.7");
  }

  @Test
  void theAverageIsRoundedHalfUp() {
    // Birthdays today, so exactly 25, 25, 25, 26: mean 25.25 -> 25.3 (half-even would give 25.2).
    givenActive(
        player(Position.GK, LocalDate.of(2001, 10, 3)),
        player(Position.CB, LocalDate.of(2001, 10, 3)),
        player(Position.CM, LocalDate.of(2001, 10, 3)),
        player(Position.ST, LocalDate.of(2000, 10, 3)));

    assertThat(service.summary().averageAge()).isEqualByComparingTo("25.3");
  }

  @Test
  void aWholeNumberAverageHasOneDecimal() {
    givenActive(player(Position.ST, LocalDate.of(2000, 10, 3)));

    assertThat(service.summary().averageAge().toPlainString()).isEqualTo("26.0");
  }

  // --- counts and lines --------------------------------------------------------------------------

  @Test
  void eachPlayerCountsOnceInTheLineOfTheirPrimaryPosition() {
    Player centreBackWhoCanPlayUpFront = player(Position.CB, LocalDate.of(2000, 10, 3));
    centreBackWhoCanPlayUpFront.setSecondaryPosition(Position.ST);
    Player injured = player(Position.LW, LocalDate.of(2000, 10, 3));
    injured.setMedicalStatus(MedicalStatus.INJURED);
    givenActive(
        player(Position.GK, LocalDate.of(2000, 10, 3)),
        centreBackWhoCanPlayUpFront,
        player(Position.RB, LocalDate.of(2000, 10, 3)),
        player(Position.DM, LocalDate.of(2000, 10, 3)),
        injured);

    SquadSummaryResponse summary = service.summary();

    assertThat(summary.playerCount()).isEqualTo(5);
    assertThat(summary.lines())
        .containsExactly(
            Map.entry(Line.GOALKEEPERS, 1),
            Map.entry(Line.DEFENSE, 2),
            Map.entry(Line.MIDFIELD, 1),
            Map.entry(Line.ATTACK, 1));
  }

  @Test
  void anEmptySquadHasNoAverageAndZeroInEveryLine() {
    givenActive();

    SquadSummaryResponse summary = service.summary();

    assertThat(summary.playerCount()).isZero();
    assertThat(summary.averageAge()).isNull();
    assertThat(summary.lines())
        .containsExactly(
            Map.entry(Line.GOALKEEPERS, 0),
            Map.entry(Line.DEFENSE, 0),
            Map.entry(Line.MIDFIELD, 0),
            Map.entry(Line.ATTACK, 0));
  }

  @Test
  void aPlayerWithoutADateOfBirthCountsButIsLeftOutOfTheAverage() {
    givenActive(
        player(Position.GK, null),
        player(Position.ST, LocalDate.of(2000, 10, 3)),
        player(Position.ST, LocalDate.of(2001, 10, 3)));

    SquadSummaryResponse summary = service.summary();

    assertThat(summary.playerCount()).isEqualTo(3);
    assertThat(summary.lines()).containsEntry(Line.GOALKEEPERS, 1).containsEntry(Line.ATTACK, 2);
    assertThat(summary.averageAge()).isEqualByComparingTo("25.5");
  }

  @Test
  void withNoDateOfBirthAtAllThereIsNoAverage() {
    givenActive(player(Position.GK, null), player(Position.ST, null));

    SquadSummaryResponse summary = service.summary();

    assertThat(summary.playerCount()).isEqualTo(2);
    assertThat(summary.averageAge()).isNull();
  }

  /** Only possible in data not written through the API, but it mustn't break the summary. */
  @Test
  void aPlayerWithoutAPrimaryPositionCountsInNoLine() {
    givenActive(player(null, LocalDate.of(2000, 10, 3)), player(Position.ST, null));

    SquadSummaryResponse summary = service.summary();

    assertThat(summary.playerCount()).isEqualTo(2);
    assertThat(summary.lines().values()).containsExactly(0, 0, 0, 1);
    assertThat(summary.averageAge()).isEqualByComparingTo("26.0");
  }

  @Test
  void onlyTheCallersActivePlayersAreLoaded() {
    givenActive();

    service.summary();

    verify(playerRepository).findByClubIdAndActive(CLUB, true);
    verifyNoMoreInteractions(playerRepository);
  }

  private void givenActive(Player... players) {
    when(playerRepository.findByClubIdAndActive(CLUB, true)).thenReturn(Arrays.asList(players));
  }

  private static Player player(Position position, LocalDate dateOfBirth) {
    Player player = new Player();
    player.setFullName("Player");
    player.setPrimaryPosition(position);
    player.setDateOfBirth(dateOfBirth);
    return player;
  }
}
