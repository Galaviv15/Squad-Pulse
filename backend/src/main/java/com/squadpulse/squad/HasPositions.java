package com.squadpulse.squad;

/**
 * Anything that carries a primary and an optional secondary {@link Position}, so {@link
 * DistinctPositions} can apply the same rule to all of them: the {@link Player} document today, and
 * the player request DTOs later.
 *
 * <p>The accessors use record-style names, so a request record with {@code primaryPosition} and
 * {@code secondaryPosition} components implements this with no extra code.
 */
public interface HasPositions {

  Position primaryPosition();

  Position secondaryPosition();
}
