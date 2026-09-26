package com.squadpulse.common;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Holds the clubId of the tenant the current thread is acting on behalf of.
 *
 * <p>Backed by a {@link ThreadLocal} rather than a request-scoped bean: it works the same way for a
 * servlet request thread today as it will for any background/worker thread later, and needs no
 * scoped-proxy machinery.
 *
 * <p>For an HTTP request, {@code auth.JwtAuthenticationFilter} sets this from the access token's
 * {@code clubId} claim and clears it in a try/finally once the request completes, so the
 * ThreadLocal never leaks across requests on a pooled worker thread. Anything else that acts on a
 * club's behalf (e.g. the club bootstrap task, tests) calls {@link #setClubId(String)} and {@link
 * #clear()} the same way.
 */
@Component
public class ClubContext {

  private static final ThreadLocal<String> CURRENT_CLUB_ID = new ThreadLocal<>();

  /** Sets the clubId for the current thread. Called per request by the JWT filter. */
  public void setClubId(String clubId) {
    CURRENT_CLUB_ID.set(clubId);
  }

  /** Clears the clubId for the current thread. Must run at the end of every request. */
  public void clear() {
    CURRENT_CLUB_ID.remove();
  }

  public Optional<String> getClubId() {
    return Optional.ofNullable(CURRENT_CLUB_ID.get());
  }

  /**
   * Returns the current thread's clubId, or throws if none is set.
   *
   * <p>Used by the club-scoped repository layer so a missing context fails loudly instead of
   * quietly falling back to an unscoped (all-clubs) query.
   */
  public String requireClubId() {
    return getClubId().orElseThrow(MissingClubContextException::new);
  }
}
