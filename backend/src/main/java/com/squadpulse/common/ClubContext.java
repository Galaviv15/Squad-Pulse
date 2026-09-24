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
 * <p>JWT auth (KAN-19) doesn't exist yet, so nothing populates this from a real token yet. {@link
 * #setClubId(String)} and {@link #clear()} are the extension point the future JWT filter will call
 * (inside a try/finally, so the ThreadLocal never leaks across requests on a pooled worker thread)
 * — until then, tests call them directly to simulate an authenticated request.
 */
@Component
public class ClubContext {

  private static final ThreadLocal<String> CURRENT_CLUB_ID = new ThreadLocal<>();

  /** Sets the clubId for the current thread. Called by the future JWT filter (KAN-19). */
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
