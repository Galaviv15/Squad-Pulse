package com.squadpulse.squad;

/**
 * A player's position on the pitch (see docs/spec.md section 05).
 *
 * <p>These codes are shown <b>as-is, in English</b> everywhere in the UI, including the Hebrew one
 * — a deliberate exception to localization (docs/spec.md section 01, CLAUDE.md rule 3). Don't
 * translate them.
 */
public enum Position {
  /** Goalkeeper. */
  GK,
  /** Center Back. */
  CB,
  /** Right Back. */
  RB,
  /** Left Back. */
  LB,
  /** Defensive Midfielder. */
  DM,
  /** Central Midfielder. */
  CM,
  /** Attacking Midfielder. */
  AM,
  /** Right Winger. */
  RW,
  /** Left Winger. */
  LW,
  /** Striker. */
  ST
}
