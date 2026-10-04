/**
 * Authentication and authorization: JWT issuing/validation, password hashing (Argon2id + pepper),
 * user management (the club's staff list at {@code GET /auth/users}, invitations, permission
 * levels), and the Title vs Permission Level model. Also the current user's own profile ({@code GET
 * /auth/users/me}) and club and staff administration: the club logo ({@code /clubs/me/logo}) and
 * staff photos ({@code /users/me/photo}, {@code /users/{id}/photo}) — both deliberately outside
 * {@code /auth}, see {@link com.squadpulse.auth.ClubLogoController}.
 *
 * <p>See docs/spec.md sections 04 (Roles &amp; permissions) and 10 (Security). Tracked in Jira
 * under KAN-10 (Auth &amp; Roles) and KAN-33 (Club &amp; Staff Administration).
 */
package com.squadpulse.auth;
