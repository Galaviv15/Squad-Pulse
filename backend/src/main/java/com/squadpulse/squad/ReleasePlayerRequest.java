package com.squadpulse.squad;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /squad/players/{id}/release}. Any other field a client sends is ignored.
 *
 * @param version the {@link Player#getVersion() version} the client loaded; the release is refused
 *     with a 409 if the player has been saved since
 */
record ReleasePlayerRequest(@NotNull Long version) {}
