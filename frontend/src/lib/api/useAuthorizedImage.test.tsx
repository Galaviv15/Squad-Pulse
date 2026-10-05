import { act, renderHook, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiError } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { useAuthorizedImage } from "./useAuthorizedImage";

// The hook needs no providers (no router, no QueryClient), so renderHook is used directly.

const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47]);

function imageAt(path: string, gate?: Promise<void>) {
  server.use(
    http.get(path, async () => {
      await gate;
      return new HttpResponse(PNG, { headers: { "Content-Type": "image/png" } });
    }),
  );
}

let created: string[];
let revoked: string[];

beforeEach(() => {
  created = [];
  revoked = [];
  vi.spyOn(URL, "createObjectURL").mockImplementation(() => {
    const url = `blob:test/${created.length + 1}`;
    created.push(url);
    return url;
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation((url) => {
    revoked.push(url);
  });
  return () => vi.restoreAllMocks();
});

describe("useAuthorizedImage", () => {
  it("does nothing for null", () => {
    // No handler: a fetch would fail the test as an unhandled request.
    const { result } = renderHook(() => useAuthorizedImage(null));

    expect(result.current).toEqual({ status: "none" });
    expect(created).toEqual([]);
  });

  it("shows the image as an object URL, and revokes it on unmount", async () => {
    imageAt("/users/me/photo");

    const { result, unmount } = renderHook(() => useAuthorizedImage("/users/me/photo"));

    expect(result.current).toEqual({ status: "loading" });
    await waitFor(() => expect(result.current).toEqual({ status: "loaded", url: "blob:test/1" }));
    unmount();
    expect(revoked).toEqual(["blob:test/1"]);
  });

  it("revokes the old URL and creates a new one when the path changes", async () => {
    imageAt("/squad/players/p1/photo");
    imageAt("/squad/players/p2/photo");
    const { result, rerender } = renderHook(({ path }) => useAuthorizedImage(path), {
      initialProps: { path: "/squad/players/p1/photo" },
    });
    await waitFor(() => expect(result.current).toEqual({ status: "loaded", url: "blob:test/1" }));

    rerender({ path: "/squad/players/p2/photo" });

    expect(revoked).toEqual(["blob:test/1"]);
    await waitFor(() => expect(result.current).toEqual({ status: "loaded", url: "blob:test/2" }));
  });

  it("reports a 404 as no image, not an error", async () => {
    server.use(http.get("/clubs/me/logo", () => apiError(404, "Not Found", "No logo")));

    const { result } = renderHook(() => useAuthorizedImage("/clubs/me/logo"));

    await waitFor(() => expect(result.current).toEqual({ status: "missing" }));
    expect(created).toEqual([]);
  });

  it("reports another failure as an error", async () => {
    server.use(http.get("/clubs/me/logo", () => apiError(403, "Forbidden", "Access denied")));

    const { result } = renderHook(() => useAuthorizedImage("/clubs/me/logo"));

    await waitFor(() => expect(result.current.status).toBe("error"));
  });

  it("ignores a late answer for a path it has left, without creating its URL", async () => {
    let releaseOld!: () => void;
    imageAt("/squad/players/p1/photo", new Promise((resolve) => (releaseOld = resolve)));
    imageAt("/squad/players/p2/photo");
    const { result, rerender } = renderHook(({ path }) => useAuthorizedImage(path), {
      initialProps: { path: "/squad/players/p1/photo" },
    });

    rerender({ path: "/squad/players/p2/photo" });
    await waitFor(() => expect(result.current).toEqual({ status: "loaded", url: "blob:test/1" }));
    await act(async () => releaseOld());

    expect(result.current).toEqual({ status: "loaded", url: "blob:test/1" });
    expect(created).toEqual(["blob:test/1"]);
    expect(revoked).toEqual([]);
  });

  it("shows loading again, not a revoked URL, when it returns to an earlier path", async () => {
    imageAt("/squad/players/p1/photo");
    let releaseP2!: () => void;
    imageAt("/squad/players/p2/photo", new Promise((resolve) => (releaseP2 = resolve)));
    const { result, rerender } = renderHook(({ path }) => useAuthorizedImage(path), {
      initialProps: { path: "/squad/players/p1/photo" },
    });
    await waitFor(() => expect(result.current).toEqual({ status: "loaded", url: "blob:test/1" }));

    rerender({ path: "/squad/players/p2/photo" });
    rerender({ path: "/squad/players/p1/photo" });

    expect(result.current).toEqual({ status: "loading" });
    await waitFor(() => expect(result.current).toEqual({ status: "loaded", url: "blob:test/2" }));
    releaseP2();
  });
});
