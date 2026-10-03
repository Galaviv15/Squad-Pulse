package com.squadpulse.squad;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins which {@link Line} each {@link Position} belongs to. The constructor already forces every
 * position to have a line; this fails when a position is added, or moved, without updating the
 * table below.
 */
class PositionTest {

  private static final Map<Position, Line> EXPECTED =
      Map.of(
          Position.GK, Line.GOALKEEPERS,
          Position.CB, Line.DEFENSE,
          Position.RB, Line.DEFENSE,
          Position.LB, Line.DEFENSE,
          Position.DM, Line.MIDFIELD,
          Position.CM, Line.MIDFIELD,
          Position.AM, Line.MIDFIELD,
          Position.RW, Line.ATTACK,
          Position.LW, Line.ATTACK,
          Position.ST, Line.ATTACK);

  @Test
  void everyPositionIsInItsExpectedLine() {
    Map<Position, Line> actual =
        Arrays.stream(Position.values())
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    Position::line,
                    (a, b) -> a,
                    () -> new EnumMap<>(Position.class)));

    assertThat(actual).hasSize(EXPECTED.size()).containsExactlyInAnyOrderEntriesOf(EXPECTED);
  }

  @Test
  void theLinesAreDeclaredFromTheGoalOutwards() {
    assertThat(Line.values())
        .containsExactly(Line.GOALKEEPERS, Line.DEFENSE, Line.MIDFIELD, Line.ATTACK);
  }
}
