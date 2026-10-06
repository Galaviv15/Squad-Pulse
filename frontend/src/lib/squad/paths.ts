/** The squad's SPA routes (see app/router.tsx). */
export const SQUAD_PATH = "/app/squad";

/** The add-player page. A static segment, so it wins over playerPath's :playerId. */
export const NEW_PLAYER_PATH = "/app/squad/new";

/** A player's card. */
export function playerPath(playerId: string): string {
  return `${SQUAD_PATH}/${encodeURIComponent(playerId)}`;
}

/** A player's edit form. */
export function editPlayerPath(playerId: string): string {
  return `${playerPath(playerId)}/edit`;
}

/** The backend's photo endpoint for a player: request it only when the player's hasPhoto is true. */
export function playerPhotoPath(playerId: string): string {
  return `/squad/players/${encodeURIComponent(playerId)}/photo`;
}
