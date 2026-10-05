import { useEffect, useState } from "react";
import { apiFetch } from "./client";
import { ApiError } from "./errors";

export type AuthorizedImage =
  | { status: "none" }
  | { status: "loading" }
  | { status: "loaded"; url: string }
  /** The endpoint answered 404: there's no image. Not an error. */
  | { status: "missing" }
  | { status: "error"; error: unknown };

interface Result {
  /** The path the image was fetched for. */
  path: string;
  image: AuthorizedImage;
}

const NONE: AuthorizedImage = { status: "none" };
const LOADING: AuthorizedImage = { status: "loading" };

/**
 * An image endpoint (photo, logo) as an object URL for an <img>. Those endpoints need the access
 * token, which an <img src> can't send, so the image is fetched through apiFetch (token, and
 * refresh-and-retry) and shown from a blob. `null` fetches nothing: callers pass null when
 * hasPhoto / hasLogo is false, and the hook never fetches just to check.
 *
 * Whoever creates an object URL must revoke it, so this is a plain effect rather than a TanStack
 * query, whose cache would keep URLs alive past unmount. The URL is revoked on unmount and when
 * the path changes; an in-flight fetch for an old path is aborted, and a late answer never sets
 * state or creates a URL.
 */
export function useAuthorizedImage(path: string | null): AuthorizedImage {
  const [result, setResult] = useState<Result | null>(null);

  useEffect(() => {
    if (path === null) {
      return;
    }
    const controller = new AbortController();
    let objectUrl: string | null = null;

    apiFetch(path, { signal: controller.signal })
      .then((response) => response.blob())
      .then((blob) => {
        if (!controller.signal.aborted) {
          objectUrl = URL.createObjectURL(blob);
          setResult({ path, image: { status: "loaded", url: objectUrl } });
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          const missing = error instanceof ApiError && error.status === 404;
          setResult({ path, image: missing ? { status: "missing" } : { status: "error", error } });
        }
      });

    return () => {
      controller.abort();
      if (objectUrl !== null) {
        URL.revokeObjectURL(objectUrl);
      }
      // Forget the result (and the revoked URL), so coming back to this path shows "loading".
      setResult(null);
    };
  }, [path]);

  if (path === null) {
    return NONE;
  }
  return result !== null && result.path === path ? result.image : LOADING;
}
