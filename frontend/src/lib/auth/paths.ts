/** Where the app starts, and where a login lands when there's no valid `next`. */
export const APP_HOME = "/app";

export const LOGIN_PATH = "/app/login";
export const FORGOT_PASSWORD_PATH = "/app/forgot-password";
export const RESET_PASSWORD_PATH = "/app/reset-password";

/** The routes that need no session. A login never returns to one of them. */
const PUBLIC_AUTH_PATHS: ReadonlySet<string> = new Set([
  LOGIN_PATH,
  FORGOT_PASSWORD_PATH,
  RESET_PASSWORD_PATH,
]);

/** The query parameter that carries where to go after login. */
export const NEXT_PARAM = "next";

/**
 * Where to go after login, from the untrusted `next` query parameter: a same-origin path under
 * /app (path, search and hash) that isn't one of the public auth routes, or APP_HOME for anything
 * else. Never trust `next` without this: it comes from the URL, which anyone can link to, so
 * without the check a crafted link could send a user to another site after they log in.
 *
 * Parsed as a URL against our own origin, so whatever the browser would turn into another origin
 * (`//evil.com`, `/\evil.com`, `https://...`, `javascript:...`) fails the origin check, and dot
 * segments (`/app/../auth`) are resolved before the prefix check.
 */
export function safeNextPath(raw: string | null | undefined): string {
  if (!raw || !raw.startsWith("/")) {
    return APP_HOME;
  }
  let url: URL;
  try {
    url = new URL(raw, window.location.origin);
  } catch {
    return APP_HOME;
  }
  if (url.origin !== window.location.origin || url.pathname.includes("//")) {
    return APP_HOME;
  }
  const path = url.pathname.length > 1 ? url.pathname.replace(/\/$/, "") : url.pathname;
  if ((path !== APP_HOME && !path.startsWith(`${APP_HOME}/`)) || PUBLIC_AUTH_PATHS.has(path)) {
    return APP_HOME;
  }
  return url.pathname + url.search + url.hash;
}

/**
 * `path` with `next` as its query parameter, or `path` alone when `next` is APP_HOME (where a login
 * goes anyway) or missing. `next` should already be safe (safeNextPath, or the current location).
 */
export function withNext(path: string, next: string | null | undefined): string {
  if (!next || next === APP_HOME) {
    return path;
  }
  return `${path}?${new URLSearchParams({ [NEXT_PARAM]: next })}`;
}
