/**
 * Router state the auth screens hand each other. The email travels in history state, never in
 * the URL, so it doesn't land in server logs or browser history as a query string.
 */
export interface AuthRouteState {
  /** Pre-fills the email field. */
  email?: string;
  /** Reset screen: a code was just requested (the "code sent" notice). */
  codeSent?: boolean;
  /** Login screen: a reset set the password but its automatic login failed. */
  passwordSet?: boolean;
}

/** Router state is whatever history holds (another app version, a hand-edited entry): check it. */
function readState(state: unknown): AuthRouteState {
  return typeof state === "object" && state !== null ? (state as AuthRouteState) : {};
}

/** The email to pre-fill from router state, or "". */
export function emailFromState(state: unknown): string {
  const { email } = readState(state);
  return typeof email === "string" ? email : "";
}

/** Whether a router-state flag is set. */
export function stateFlag(state: unknown, flag: "codeSent" | "passwordSet"): boolean {
  return readState(state)[flag] === true;
}
