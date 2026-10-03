package com.squadpulse.squad;

/**
 * A player's position on the pitch (see docs/spec.md section 05).
 *
 * <p>These codes are shown <b>as-is, in English</b> everywhere in the UI, including the Hebrew one
 * — a deliberate exception to localization (docs/spec.md section 01, CLAUDE.md rule 3). Don't
 * translate them.
 *
 * <p>Each position belongs to one {@link Line}; this is the only place that mapping is defined.
 */
public enum Position {
  /** Goalkeeper. */
  GK(Line.GOALKEEPERS),
  /** Center Back. */
  CB(Line.DEFENSE),
  /** Right Back. */
  RB(Line.DEFENSE),
  /** Left Back. */
  LB(Line.DEFENSE),
  /** Defensive Midfielder. */
  DM(Line.MIDFIELD),
  /** Central Midfielder. */
  CM(Line.MIDFIELD),
  /** Attacking Midfielder. */
  AM(Line.MIDFIELD),
  /** Right Winger. */
  RW(Line.ATTACK),
  /** Left Winger. */
  LW(Line.ATTACK),
  /** Striker. */
  ST(Line.ATTACK);

  private final Line line;

  Position(Line line) {
    this.line = line;
  }

  public Line line() {
    return line;
  }
}
