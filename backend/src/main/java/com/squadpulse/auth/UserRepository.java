package com.squadpulse.auth;

import com.squadpulse.common.GloballyScoped;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Repository for {@link User}. Like every repository for a {@link
 * com.squadpulse.common.ClubScopedEntity}, the standard CRUD methods go through {@link
 * com.squadpulse.common.ClubScopedRepositoryImpl} and are automatically club-scoped.
 */
public interface UserRepository extends MongoRepository<User, String> {

  /**
   * Looks a user up by email across <b>all</b> clubs — deliberately not club-scoped.
   *
   * <p>Login needs this: it runs before any club context exists, and finding out which club the
   * caller belongs to is exactly what it's for. It's safe because email carries a global unique
   * index (see {@link User}), so this can match at most one user — it's a key lookup, not a filter
   * that could return several clubs' records. The caller must pass the email through {@link
   * User#normalizeEmail(String)} first.
   *
   * <p>Anything that runs with a club context (i.e. after login) should not use this.
   */
  @GloballyScoped
  Optional<User> findByEmail(String email);
}
