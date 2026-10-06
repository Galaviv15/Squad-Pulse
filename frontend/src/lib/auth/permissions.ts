/**
 * auth.PermissionLevel, highest first: the backend's role hierarchy (ADMIN > EDIT_FULL >
 * EDIT_PARTIAL > VIEW_ONLY) follows this order, and so does hasPermission.
 */
export const PERMISSION_LEVELS = ["ADMIN", "EDIT_FULL", "EDIT_PARTIAL", "VIEW_ONLY"] as const;

export type PermissionLevel = (typeof PERMISSION_LEVELS)[number];

/**
 * Whether a user at `level` may do what needs `required`: the same level or a higher one, as the
 * backend's RoleHierarchy decides. Only for what the UI shows (a button, a link); the backend
 * checks every request itself.
 */
export function hasPermission(level: PermissionLevel, required: PermissionLevel): boolean {
  return PERMISSION_LEVELS.indexOf(level) <= PERMISSION_LEVELS.indexOf(required);
}
