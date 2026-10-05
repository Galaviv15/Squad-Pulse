import { describe, expect, it } from "vitest";
import { createQueryClient, shouldRetryQuery } from "./queryClient";

const httpError = (status: number) => Object.assign(new Error(`HTTP ${status}`), { status });

describe("shouldRetryQuery", () => {
  it.each([400, 401, 403, 404, 409, 499])("never retries a %i", (status) => {
    expect(shouldRetryQuery(0, httpError(status))).toBe(false);
  });

  it("retries a 500 twice", () => {
    expect(shouldRetryQuery(0, httpError(500))).toBe(true);
    expect(shouldRetryQuery(1, httpError(500))).toBe(true);
    expect(shouldRetryQuery(2, httpError(500))).toBe(false);
  });

  it("retries a network failure (an Error without a status) twice", () => {
    const networkError = new TypeError("Failed to fetch");

    expect(shouldRetryQuery(0, networkError)).toBe(true);
    expect(shouldRetryQuery(1, networkError)).toBe(true);
    expect(shouldRetryQuery(2, networkError)).toBe(false);
  });

  it("treats a non-numeric status as no status", () => {
    expect(shouldRetryQuery(0, { status: "404" })).toBe(true);
  });
});

describe("createQueryClient", () => {
  it("uses shouldRetryQuery for queries and never retries mutations", () => {
    const options = createQueryClient().getDefaultOptions();

    expect(options.queries?.retry).toBe(shouldRetryQuery);
    expect(options.mutations?.retry).toBe(0);
  });
});
