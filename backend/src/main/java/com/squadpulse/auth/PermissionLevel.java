package com.squadpulse.auth;

/**
 * The access level actually checked on every request, independent of a user's {@link Title}. See
 * docs/spec.md sections 04 and 10.
 *
 * <p><b>The declaration order is meaningful: it is the permission hierarchy</b>, highest first.
 * Each level includes every level declared after it, so {@code ADMIN > EDIT_FULL > EDIT_PARTIAL >
 * VIEW_ONLY}. {@link SecurityConfig#permissionLevelHierarchy()} builds the Spring Security {@code
 * RoleHierarchy} from this order, so {@code @PreAuthorize("hasAuthority('EDIT_FULL')")} also admits
 * an {@code ADMIN}. Reordering, adding or removing a constant changes who can reach what, and
 * {@code PermissionLevelHierarchyTest} fails until it's updated on purpose.
 *
 * <p>Endpoints require the <b>lowest</b> level that may use them, and read endpoints require {@code
 * VIEW_ONLY} explicitly.
 *
 * <p><b>{@link #EDIT_PARTIAL} is intentionally unused by any endpoint for now.</b> It exists so an
 * {@code ADMIN} can already assign it, and its concrete meaning will be defined once feature
 * endpoints (players etc.) exist. Until an endpoint requires it, it grants exactly what {@link
 * #VIEW_ONLY} grants.
 */
public enum PermissionLevel {
  ADMIN,
  EDIT_FULL,
  EDIT_PARTIAL,
  VIEW_ONLY
}
