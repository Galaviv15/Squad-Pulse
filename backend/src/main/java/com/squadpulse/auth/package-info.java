/**
 * Authentication and authorization: JWT issuing/validation, password hashing (Argon2id + pepper),
 * user management (the club's staff list at {@code GET /auth/users}, invitations, permission
 * levels, deactivation and re-activation), and the Title vs Permission Level model. Also the
 * current user's own profile ({@code GET /auth/users/me}) and club and staff administration: the
 * club's settings ({@code /clubs/me}), its logo ({@code /clubs/me/logo}) and staff photos ({@code
 * /users/me/photo}, {@code /users/{id}/photo}) — all deliberately outside {@code /auth}, see {@link
 * com.squadpulse.auth.ClubLogoController}.
 *
 * <p>Exactly the user-management writes and {@code PATCH /clubs/me} re-check their caller ({@link
 * com.squadpulse.auth.ActiveCallerCheck}), and so must any new endpoint of those two kinds; the
 * club-logo and staff-photo writes deliberately don't.
 *
 * <p>See docs/spec.md sections 04 (Roles &amp; permissions) and 10 (Security). Tracked in Jira
 * under KAN-10 (Auth &amp; Roles) and KAN-33 (Club &amp; Staff Administration).
 */
package com.squadpulse.auth;
