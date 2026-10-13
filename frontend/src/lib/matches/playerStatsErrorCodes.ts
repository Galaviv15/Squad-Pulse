/**
 * The player statistics API's 409 codes (ApiError.code), as the backend's ConflictException
 * subclasses declare them. Codes are stable, so screens compare against these, never against the
 * English message.
 */
export const PLAYER_STATS_ERROR_CODES = {
  /** match.PlayerAlreadyLinkedException: the squad player is linked to another scraped player. */
  PLAYER_ALREADY_LINKED: "PLAYER_ALREADY_LINKED",
  /**
   * match.StatsRowAlreadyLinkedException (KAN-77): creating a player from a scraped row that's
   * linked to an existing player; nothing was created.
   */
  STATS_ROW_ALREADY_LINKED: "STATS_ROW_ALREADY_LINKED",
  /**
   * common.GlobalExceptionHandler's generic optimistic-lock 409; from the create-and-link endpoint
   * (KAN-77): a concurrent write to the row's link won, nothing was created.
   */
  CONCURRENT_MODIFICATION: "CONCURRENT_MODIFICATION",
} as const;
