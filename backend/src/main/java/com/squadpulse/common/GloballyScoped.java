package com.squadpulse.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a custom method on a club-scoped repository as <b>deliberately not</b> club-scoped.
 *
 * <p>Custom/derived query methods bypass {@link ClubScopedRepositoryImpl} entirely, so the ArchUnit
 * rule in {@code ClubScopedRepositoryRules} normally requires every one of them to include {@code
 * ClubId} in its name. This annotation is the only other way to satisfy that rule.
 *
 * <p>It is safe only for a lookup by a value that is <b>unique across the whole system</b> — e.g.
 * {@code UserRepository.findByEmail(...)}, where email carries a global unique index and the lookup
 * has to happen during login, before any club context exists. Such a query can match at most one
 * document, and establishing which club that document belongs to is the whole point of the call. It
 * is <b>never</b> safe for a filter that could match records from more than one club (a name, a
 * status, a date range, ...): that would be exactly the cross-club leak this layer exists to
 * prevent.
 *
 * <p><b>Uses of this annotation should be rare, and each one should be reviewed on its own
 * merits</b> — check that the looked-up value really is backed by a global unique index, and that
 * the caller genuinely has no club context to scope by. Any caller that <i>does</i> have a club
 * context should use a {@code ...AndClubId} method instead.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GloballyScoped {}
