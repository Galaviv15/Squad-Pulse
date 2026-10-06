import { ApiError } from "@/lib/api/errors";

/** A form's field errors: an i18n key per invalid field. */
export type FieldErrors<F extends string> = Partial<Record<F, string>>;

/**
 * Whether a failed request says nothing about what was sent, so the form shows "no connection,
 * try again": a 5xx (the dev proxy's 502 for a stopped backend), no response at all
 * (NetworkError), or a 2xx the client couldn't read (a token response without a token).
 */
export function isServerUnreachable(error: unknown): boolean {
  return !(error instanceof ApiError) || error.status >= 500;
}

/**
 * A 400's field errors as i18n keys, for the fields named in `keys` (the backend's field name →
 * the key of that field's Hebrew message). The backend's English messages are never shown; only
 * which field it named matters. Null when the 400 names none of these fields.
 */
function serverFieldErrors<F extends string>(
  error: ApiError,
  keys: Record<F, string>,
): FieldErrors<F> | null {
  const errors: FieldErrors<F> = {};
  let found = false;
  for (const field of Object.keys(keys) as F[]) {
    if (error.fieldErrors[field]) {
      errors[field] = keys[field];
      found = true;
    }
  }
  return found ? errors : null;
}

/** What a form shows after its request failed: a form-level message, or field errors. */
export interface SubmitErrors<F extends string> {
  formError: string | null;
  fieldErrors: FieldErrors<F>;
}

/**
 * The messages for a failed form request: "no connection" for a 5xx / no response, the form's own
 * message for the statuses in `byStatus` (401, 429, ...), the fields' messages for a 400 that
 * names one of `fieldKeys`, a generic "invalid" for any other 400, and "unexpected" otherwise.
 */
export function submitErrors<F extends string>(
  error: unknown,
  byStatus: Partial<Record<number, string>>,
  fieldKeys: Record<F, string>,
): SubmitErrors<F> {
  if (isServerUnreachable(error)) {
    return { formError: "auth.errors.serverUnreachable", fieldErrors: {} };
  }
  const apiError = error as ApiError;
  const own = byStatus[apiError.status];
  if (own) {
    return { formError: own, fieldErrors: {} };
  }
  if (apiError.status === 400) {
    const fieldErrors = serverFieldErrors(apiError, fieldKeys);
    return fieldErrors
      ? { formError: null, fieldErrors }
      : { formError: "auth.errors.invalidRequest", fieldErrors: {} };
  }
  return { formError: "auth.errors.unexpected", fieldErrors: {} };
}

/** The trimmed email's i18n error key, or undefined if it's acceptable. Mirrors the backend. */
export function emailError(email: string): string | undefined {
  if (email === "") {
    return "auth.validation.emailRequired";
  }
  // @Size(max = 254) on every request that takes an email.
  return email.length > 254 ? "auth.validation.emailTooLong" : undefined;
}
