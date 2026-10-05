import { apiUrl } from "@/lib/config";
import { NetworkError } from "./errors";

/**
 * One fetch to the backend, nothing more: the URL comes from apiUrl, and a failure without a
 * response becomes a NetworkError. An abort is rethrown as is, so TanStack Query sees a
 * cancellation. Doesn't look at the status and doesn't touch the session: the request layer
 * (client.ts) and the session (session.ts) build on it.
 *
 * Credentials are "same-origin", stated rather than left to the default: the refresh cookie
 * (HttpOnly, Path=/auth) reaches the backend only on our own origin. Never "include", which would
 * hide a cross-origin mistake by sending the cookie anyway.
 */
export async function send(path: string, init: RequestInit): Promise<Response> {
  try {
    return await fetch(apiUrl(path), { ...init, credentials: "same-origin" });
  } catch (error) {
    if (init.signal?.aborted || (error instanceof DOMException && error.name === "AbortError")) {
      throw error;
    }
    throw new NetworkError(error);
  }
}
