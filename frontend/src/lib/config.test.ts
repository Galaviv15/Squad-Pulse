import { afterEach, describe, expect, it, vi } from "vitest";

// config.ts reads the env when it's first imported, so each case stubs the env and then loads a
// fresh copy of the module.
async function loadConfig(baseUrl: string | undefined) {
  vi.resetModules();
  vi.stubEnv("VITE_API_BASE_URL", baseUrl);
  return import("./config");
}

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("apiUrl", () => {
  it("returns a same-origin relative path when no base URL is set", async () => {
    const { API_BASE_URL, apiUrl } = await loadConfig(undefined);

    expect(API_BASE_URL).toBe("");
    expect(apiUrl("/auth/login")).toBe("/auth/login");
  });

  it("returns a same-origin relative path when the base URL is empty", async () => {
    const { apiUrl } = await loadConfig("");

    expect(apiUrl("/auth/login")).toBe("/auth/login");
  });

  it("prefixes the base URL", async () => {
    const { apiUrl } = await loadConfig("https://api.example.com");

    expect(apiUrl("/squad/players")).toBe("https://api.example.com/squad/players");
  });

  it("drops a trailing slash from the base URL", async () => {
    const { API_BASE_URL, apiUrl } = await loadConfig("https://api.example.com/");

    expect(API_BASE_URL).toBe("https://api.example.com");
    expect(apiUrl("/squad/players")).toBe("https://api.example.com/squad/players");
  });

  it("rejects a path without a leading slash", async () => {
    const { apiUrl } = await loadConfig("");

    expect(() => apiUrl("squad/players")).toThrow('API path must start with "/"');
  });
});
