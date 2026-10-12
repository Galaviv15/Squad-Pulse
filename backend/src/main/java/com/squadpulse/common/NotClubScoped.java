package com.squadpulse.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a MongoDB document class as <b>deliberately not</b> tenant-scoped, so its repository is
 * built on plain {@link org.springframework.data.mongodb.repository.support.SimpleMongoRepository}
 * instead of {@link ClubScopedRepositoryImpl}.
 *
 * <p>{@link ClubScopedRepositoryFactory} requires every repository's entity to either extend {@link
 * ClubScopedEntity} or carry this annotation, and refuses to start otherwise — so forgetting to
 * extend {@link ClubScopedEntity} on tenant data fails loudly instead of silently producing an
 * unscoped repository.
 *
 * <p>It is safe only for data that isn't owned by any single club. <b>Uses of this annotation
 * should be rare, and each one should be reviewed on its own merits</b> — anything a club's users
 * create belongs in a {@link ClubScopedEntity}. The uses today:
 *
 * <ul>
 *   <li>{@code auth.Club}, the tenant root itself: a club doesn't belong to a club.
 *   <li>{@code match.LeagueStandingsSnapshot} (KAN-66), a football league's public table, the same
 *       for every club in that league: the first tenant-shared data, and the fourth deliberate
 *       exception to the isolation layer (after {@link GloballyScoped} finders, {@code
 *       auth.UserVersionBackfill} and the GridFS image store — docs/spec.md section 03). Safe only
 *       because its one write path is the scraper's service-token ingest, it's stored only for a
 *       league some club is mapped to, reads take the league and season only from the caller's own
 *       club mapping (no endpoint takes a league id), and nothing club-owned is ever stored in it —
 *       see its Javadoc.
 * </ul>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NotClubScoped {}
