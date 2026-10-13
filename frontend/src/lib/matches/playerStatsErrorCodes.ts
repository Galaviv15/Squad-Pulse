/**
 * The player statistics API's 409 codes (ApiError.code), as the backend's ConflictException
 * subclasses declare them. Codes are stable, so screens compare against these, never against the
 * English message.
 */
export const PLAYER_STATS_ERROR_CODES = {
  /** match.PlayerAlreadyLinkedException: the squad player is linked to another scraped player. */
  PLAYER_ALREADY_LINKED: "PLAYER_ALREADY_LINKED",
} as const;
