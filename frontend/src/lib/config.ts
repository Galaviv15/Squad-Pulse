/**
 * The backend's base URL, from VITE_API_BASE_URL at build time. Empty by default, so API URLs
 * are same-origin relative paths ("/auth/login"), served by the Vite dev proxy in development
 * and a reverse proxy in production. A trailing "/" is dropped.
 */
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? "").trim().replace(/\/+$/, "");

/**
 * The URL of a backend endpoint. This is the only place API URLs are built: everything that
 * calls the backend goes through it. The path must start with "/", e.g. apiUrl("/clubs/me").
 */
export function apiUrl(path: string): string {
  if (!path.startsWith("/")) {
    throw new Error(`API path must start with "/": ${path}`);
  }
  return API_BASE_URL + path;
}
