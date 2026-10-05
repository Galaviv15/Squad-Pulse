/**
 * The backend's JSON error body (common.ApiErrorResponse): every error the application itself
 * answers has this shape. Entries in `details` are usually "<name>: <message>", where the name is a
 * field, parameter or path such as "players[2].position"; a few have no name (a cross-field rule).
 */
export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  details: string[];
}

/**
 * An HTTP error response: any non-2xx answer, whatever its body. `status` is always the response's
 * numeric status; shouldRetryQuery (src/lib/queryClient.ts) relies on that to never retry a 4xx.
 * When the body is an ApiErrorBody its fields are kept, with `details` split into `fieldErrors`
 * and `generalErrors`. Otherwise (Tomcat's HTML page, the dev proxy's empty 502, broken JSON) the
 * message is a fixed fallback: the raw body is never put into it. The texts are the backend's
 * English ones; screens show their own, translated, texts for a status or field.
 */
export class ApiError extends Error {
  override readonly name = "ApiError";
  readonly status: number;
  /** The body's reason phrase ("Unauthorized"), or null when the body wasn't an ApiErrorBody. */
  readonly error: string | null;
  readonly details: readonly string[];
  /** The named `details` entries, by name exactly as sent ("players[2].position"). */
  readonly fieldErrors: Readonly<Record<string, readonly string[]>>;
  /** The `details` entries without a name. */
  readonly generalErrors: readonly string[];

  constructor(status: number, body: ApiErrorBody | null) {
    super(body?.message ?? `Request failed with status ${status}`);
    this.status = status;
    this.error = body?.error ?? null;
    this.details = body?.details ?? [];

    const fieldErrors: Record<string, string[]> = Object.create(null);
    const generalErrors: string[] = [];
    for (const entry of this.details) {
      const named = splitDetail(entry);
      if (named) {
        (fieldErrors[named.name] ??= []).push(named.message);
      } else {
        generalErrors.push(entry);
      }
    }
    this.fieldErrors = fieldErrors;
    this.generalErrors = generalErrors;
  }
}

/**
 * No response at all: the network failed, or the server couldn't be reached directly. It has no
 * `status`, so shouldRetryQuery retries it. (Through the Vite dev proxy a stopped backend is a 502
 * ApiError instead.) An aborted request is never one of these: the AbortError is passed on as is.
 */
export class NetworkError extends Error {
  override readonly name = "NetworkError";
  /** What fetch rejected with. (The tsconfig lib is ES2020, which has no Error `cause` option.) */
  readonly cause: unknown;

  constructor(cause: unknown) {
    super("The server could not be reached");
    this.cause = cause;
  }
}

/**
 * "<name>: <message>", split on the first ": ". The name must be non-empty and contain no
 * whitespace (field names and paths never do), so a nameless message that merely contains ": "
 * stays a general error.
 */
function splitDetail(entry: string): { name: string; message: string } | null {
  const separator = entry.indexOf(": ");
  if (separator <= 0) {
    return null;
  }
  const name = entry.slice(0, separator);
  return /\s/.test(name) ? null : { name, message: entry.slice(separator + 2) };
}

function isApiErrorBody(value: unknown): value is ApiErrorBody {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const body = value as Record<string, unknown>;
  return (
    typeof body.message === "string" &&
    typeof body.error === "string" &&
    Array.isArray(body.details) &&
    body.details.every((entry) => typeof entry === "string")
  );
}

/** The ApiError for a non-2xx response. Reads (consumes) the body. */
export async function apiErrorFrom(response: Response): Promise<ApiError> {
  let body: unknown = null;
  try {
    body = JSON.parse(await response.text());
  } catch {
    // Not JSON (HTML, plain text, empty) or the body couldn't be read: use the fallback.
  }
  return new ApiError(response.status, isApiErrorBody(body) ? body : null);
}
