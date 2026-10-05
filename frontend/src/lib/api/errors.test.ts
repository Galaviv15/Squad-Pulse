import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { shouldRetryQuery } from "@/lib/queryClient";
import { apiError } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { apiFetch } from "./client";
import { ApiError, NetworkError } from "./errors";
import { createBrowserSession } from "./session";

/** What apiFetch rejects with for GET /squad/players, answered by `response`. */
async function errorFor(response: () => Response): Promise<unknown> {
  server.use(http.get("/squad/players", response));
  return apiFetch("/squad/players", {}, createBrowserSession()).catch((error: unknown) => error);
}

describe("ApiError", () => {
  it("keeps the body of an ApiErrorResponse and splits its details", async () => {
    const error = await errorFor(() =>
      apiError(400, "Bad Request", "Validation failed", [
        "players[2].position: must not be null",
        "name: must not be blank",
        "name: size must be between 1 and 100",
        "minAge must not be greater than maxAge",
        "Something went wrong: try again",
      ]),
    );

    expect(error).toBeInstanceOf(ApiError);
    const apiErr = error as ApiError;
    expect(apiErr.status).toBe(400);
    expect(apiErr.error).toBe("Bad Request");
    expect(apiErr.message).toBe("Validation failed");
    expect(apiErr.details).toHaveLength(5);
    expect({ ...apiErr.fieldErrors }).toEqual({
      "players[2].position": ["must not be null"],
      name: ["must not be blank", "size must be between 1 and 100"],
    });
    expect(apiErr.generalErrors).toEqual([
      "minAge must not be greater than maxAge",
      "Something went wrong: try again",
    ]);
  });

  it("has no field or general errors for empty details (broken JSON sent to the backend)", async () => {
    const error = (await errorFor(() =>
      apiError(400, "Bad Request", "Malformed JSON request"),
    )) as ApiError;

    expect(error.message).toBe("Malformed JSON request");
    expect(error.fieldErrors).toEqual({});
    expect(error.generalErrors).toEqual([]);
  });

  it.each([
    [
      "an HTML 500 page",
      500,
      () =>
        new HttpResponse("<html><body>Internal Server Error</body></html>", {
          status: 500,
          headers: { "Content-Type": "text/html" },
        }),
    ],
    [
      "an empty 502 (the dev proxy, backend down)",
      502,
      () => new HttpResponse(null, { status: 502 }),
    ],
    [
      "malformed JSON",
      500,
      () =>
        new HttpResponse('{"message": "oops"', {
          status: 500,
          headers: { "Content-Type": "application/json" },
        }),
    ],
    ["JSON of another shape", 504, () => HttpResponse.json({ oops: true }, { status: 504 })],
  ])("falls back to a fixed message for %s", async (_name, status, response) => {
    const error = (await errorFor(response)) as ApiError;

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(status);
    expect(error.message).toBe(`Request failed with status ${status}`);
    expect(error.error).toBeNull();
    expect(error.details).toEqual([]);
  });

  it("is a NetworkError, without a status, when there is no response", async () => {
    const error = await errorFor(() => HttpResponse.error());

    expect(error).toBeInstanceOf(NetworkError);
    expect(error).not.toHaveProperty("status");
  });
});

describe("shouldRetryQuery with the API error types", () => {
  it("never retries an ApiError with a 4xx", () => {
    for (const status of [400, 401, 403, 404, 409, 413]) {
      expect(shouldRetryQuery(0, new ApiError(status, null))).toBe(false);
    }
  });

  it("retries an ApiError with a 5xx", () => {
    expect(shouldRetryQuery(0, new ApiError(500, null))).toBe(true);
    expect(shouldRetryQuery(0, new ApiError(502, null))).toBe(true);
  });

  it("retries a NetworkError", () => {
    expect(shouldRetryQuery(0, new NetworkError(new TypeError("Failed to fetch")))).toBe(true);
  });
});
