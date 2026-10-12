import type { Game } from "./types";

/**
 * Where a game stands on Israel's `today` (yyyy-MM-dd, from israelToday): played (it has a
 * result), upcoming (no result, today or later), or "no result" (no result and its date has
 * passed: postponed, or the site isn't updated yet — never shown as a score).
 */
export type GameStatus = "played" | "upcoming" | "noResult";

export function gameStatus(game: Game, today: string): GameStatus {
  if (game.result !== null) {
    return "played";
  }
  // Both yyyy-MM-dd: compared as strings, never through new Date("yyyy-MM-dd") (UTC midnight).
  return game.date >= today ? "upcoming" : "noResult";
}

/**
 * The next game: the first not played whose date is today or later, in the server's order (by
 * date, then kickoff time, an unset time last). A game of today stays next until it has a result,
 * even after its kickoff. null when there's none.
 */
export function nextGame(games: Game[], today: string): Game | null {
  return games.find((game) => gameStatus(game, today) === "upcoming") ?? null;
}
