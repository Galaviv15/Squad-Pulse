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
 * <p>It is safe only for data that isn't owned by any single club. The one intended use is {@code
 * auth.Club}, the tenant root itself: a club doesn't belong to a club. <b>Uses of this annotation
 * should be rare, and each one should be reviewed on its own merits</b> — anything a club's users
 * create or read belongs in a {@link ClubScopedEntity}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NotClubScoped {}
