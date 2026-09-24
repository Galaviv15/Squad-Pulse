package com.squadpulse.auth;

/**
 * A user's professional role — for display and organization only, never for access checks (that's
 * {@link PermissionLevel}). See docs/spec.md section 04 for each title's default permission level.
 */
public enum Title {
  CLUB_MANAGER,
  HEAD_COACH,
  ASSISTANT_COACH,
  GOALKEEPING_COACH,
  FITNESS_COACH,
  ANALYST
}
