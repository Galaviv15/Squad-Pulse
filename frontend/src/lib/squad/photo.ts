import { ApiError } from "@/lib/api/errors";
import { PLAYER_ERROR_CODES } from "./errorCodes";

/**
 * The photo types the API accepts (common.ImageType: JPEG, PNG, WebP), as the browser reports a
 * picked file's type. The server decides by the file's content, not by this: a file that passes
 * here but isn't really an image still gets the server's 400.
 */
export const PHOTO_TYPES = ["image/jpeg", "image/png", "image/webp"] as const;

/** The file picker's `accept`: the same types. */
export const PHOTO_ACCEPT = PHOTO_TYPES.join(",");

/**
 * squadpulse.images.max-size (2MB, a Spring DataSize: 2 × 1024 × 1024 bytes). The server refuses
 * a larger file (common.ImageValidator: `size > max`), so a file of exactly this size is accepted.
 */
export const PHOTO_MAX_BYTES = 2 * 1024 * 1024;

/**
 * The client-side check of a picked photo, before any request: the i18n key of what's wrong, or
 * null when it may be sent. Empty first (an empty file often has no type either), then the type,
 * then the size.
 */
export function photoFileError(file: Pick<File, "type" | "size">): string | null {
  if (file.size === 0) {
    return "squad.photo.errors.empty";
  }
  if (!(PHOTO_TYPES as readonly string[]).includes(file.type)) {
    return "squad.photo.errors.type";
  }
  if (file.size > PHOTO_MAX_BYTES) {
    return "squad.photo.errors.tooLarge";
  }
  return null;
}

/**
 * What a failed upload says (an i18n key): 400 not an image (the server reads the content: a
 * renamed PDF, a corrupt file); 413 too large; 409 PLAYER_RELEASED released meanwhile; 403 no
 * permission; 404 the player is gone; anything else (5xx, no response: a connection reset for a
 * body far over the limit, see server.tomcat.max-swallow-size) the generic failure.
 */
export function photoUploadErrorKey(error: unknown): string {
  if (error instanceof ApiError) {
    switch (error.status) {
      case 400:
        return "squad.photo.errors.notAnImage";
      case 413:
        return "squad.photo.errors.tooLarge";
      case 403:
        return "squad.noPermission";
      case 404:
        return "squad.player.notFound";
      case 409:
        if (error.code === PLAYER_ERROR_CODES.PLAYER_RELEASED) {
          return "squad.form.released";
        }
    }
  }
  return "squad.dialog.failed";
}
