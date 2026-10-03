package com.squadpulse.squad;

/**
 * The line of the team a {@link Position} belongs to, for the squad summary (see {@link
 * Position#line()}). Declared from the goal outwards, which is also the order the summary lists
 * them in.
 */
public enum Line {
  GOALKEEPERS,
  DEFENSE,
  MIDFIELD,
  ATTACK
}
