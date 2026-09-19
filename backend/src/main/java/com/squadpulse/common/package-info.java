/**
 * Cross-cutting infrastructure shared by every other module — most importantly, the central {@code
 * clubId} isolation layer (a base repository / aspect that injects a {@code clubId} filter into
 * every tenant-scoped query) plus shared error handling.
 *
 * <p><b>This is the single most safety-critical package in the codebase.</b> Every PR that touches
 * data access should be checked against it. See docs/spec.md section 03 (Multi-tenancy) and
 * CLAUDE.md standing rule 4.
 */
package com.squadpulse.common;
