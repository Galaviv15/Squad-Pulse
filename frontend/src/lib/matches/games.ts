import type { Game } from "./types";

/**
 * Where a game stands on Israel's `today` (yyyy-MM-dd, from israelToday): played (both 90-minute
 * scores set, league or cup), upcoming (no score, today or later), or "no result" (no score and its
 * date has passed: postponed, or the site isn't updated yet — never shown as a score). Never from
 * `result`: a played cup game can have a null one (a level shootout, bad data).
 */
export type GameStatus = "played" | "upcoming" | "noResult";

export function gameStatus(game: Game, today: string): GameStatus {
  if (game.ownScore !== null && game.opponentScore !== null) {
    return "played";
  }
  // Both yyyy-MM-dd: compared as strings, never through new Date("yyyy-MM-dd") (UTC midnight).
  return game.date >= today ? "upcoming" : "noResult";
}

/**
 * The next game: the first not played whose date is today or later, in the server's order (by
 * date, then kickoff time, an unset time last), league or cup alike. A game of today stays next
 * until it has a score, even after its kickoff. null when there's none.
 */
export function nextGame(games: Game[], today: string): Game | null {
  return games.find((game) => gameStatus(game, today) === "upcoming") ?? null;
}
