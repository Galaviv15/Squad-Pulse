/**
 * Authentication and authorization: JWT issuing/validation, password hashing (Argon2id + pepper),
 * user management, and the Title vs Permission Level model. Also the current user's own profile
 * ({@code GET /auth/users/me}) and, later, club and staff administration (the club logo, staff
 * photos).
 *
 * <p>See docs/spec.md sections 04 (Roles &amp; permissions) and 10 (Security). Tracked in Jira
 * under KAN-10 (Auth &amp; Roles) and KAN-33 (Club &amp; Staff Administration).
 */
package com.squadpulse.auth;
