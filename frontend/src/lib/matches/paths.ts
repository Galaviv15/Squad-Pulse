import { MATCHES_PATH } from "./tab";

/** A played game's details page (KAN-81); `detailsId` is our opaque id, never a site id. */
export function gamePath(detailsId: string): string {
  return `${MATCHES_PATH}/games/${encodeURIComponent(detailsId)}`;
}
