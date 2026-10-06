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
  /** The path and refresh key the image was fetched for. */
  path: string;
  refreshKey: number;
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
 *
 * `refreshKey` fetches the same path again when it changes, e.g. after the image was replaced
 * (same endpoint, new content). It's treated like a path change: the old URL is revoked, an
 * in-flight fetch aborted, and "loading" shown until the new image arrives. Omitted, it never
 * changes, so the image is fetched once per path.
 */
export function useAuthorizedImage(path: string | null, refreshKey = 0): AuthorizedImage {
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
          setResult({ path, refreshKey, image: { status: "loaded", url: objectUrl } });
        }
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted) {
          const missing = error instanceof ApiError && error.status === 404;
          setResult({
            path,
            refreshKey,
            image: missing ? { status: "missing" } : { status: "error", error },
          });
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
  }, [path, refreshKey]);

  if (path === null) {
    return NONE;
  }
  return result !== null && result.path === path && result.refreshKey === refreshKey
    ? result.image
    : LOADING;
}
