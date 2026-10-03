package com.squadpulse.squad;

import java.math.BigDecimal;
import java.util.Map;

/**
 * The squad summary as returned by the API: the club's active players only.
 *
 * @param averageAge mean exact age, 1 decimal; {@code null} (written as {@code null}, not omitted)
 *     if no active player has a date of birth
 * @param lines active players per {@link Line} by primary position: always every line, in {@link
 *     Line} order, {@code 0} for an empty one
 */
record SquadSummaryResponse(int playerCount, BigDecimal averageAge, Map<Line, Integer> lines) {}
