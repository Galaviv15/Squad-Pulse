package com.squadpulse.auth;

/**
 * The access level actually checked on every request, independent of a user's {@link Title}. See
 * docs/spec.md sections 04 and 10.
 */
public enum PermissionLevel {
  ADMIN,
  EDIT_FULL,
  EDIT_PARTIAL,
  VIEW_ONLY
}
