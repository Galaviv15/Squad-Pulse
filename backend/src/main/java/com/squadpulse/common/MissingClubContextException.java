package com.squadpulse.common;

/**
 * Thrown when a club-scoped repository method runs with no clubId set in {@link ClubContext}.
 *
 * <p>Reaching this in production means a request hit the data layer without going through
 * club-scoped authentication — a server-side wiring bug, not a client error, since the JWT filter
 * ({@code auth.JwtAuthenticationFilter}) always sets a clubId for an authenticated request.
 */
public class MissingClubContextException extends RuntimeException {

  public MissingClubContextException() {
    super(
        "No clubId set in ClubContext for the current thread — the request never went through"
            + " club-scoped authentication.");
  }
}
