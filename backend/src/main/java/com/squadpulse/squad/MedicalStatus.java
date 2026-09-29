package com.squadpulse.squad;

/**
 * A player's current fitness (see docs/spec.md section 05). Deliberately coarse for V1: extended
 * injury fields (injury type, expected return date) come later.
 */
public enum MedicalStatus {
  FIT,
  INJURED
}
