/**
 * Router state the squad pages hand each other: a one-shot notice carried in history state,
 * never in the URL. History state survives a reload and Back (React Router keeps it in
 * window.history.state), so the page that shows the notice clears it from its entry at once.
 */
export interface SquadRouteState {
  /** Squad page: this player was just deleted from their card ("<name> נמחק לצמיתות."). */
  deletedPlayerName?: string;
}

/** The state the card navigates to the squad with after a delete. */
export function deletedPlayerState(name: string): SquadRouteState {
  return { deletedPlayerName: name };
}

/** The deleted player's name from router state, or null. State is whatever history holds: check it. */
export function deletedPlayerNameFromState(state: unknown): string | null {
  if (typeof state !== "object" || state === null) {
    return null;
  }
  const { deletedPlayerName } = state as SquadRouteState;
  return typeof deletedPlayerName === "string" ? deletedPlayerName : null;
}
