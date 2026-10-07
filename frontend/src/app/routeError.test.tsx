import { fireEvent, screen, within } from "@testing-library/react";
import type { RouteObject } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import he from "@/i18n/locales/he.json";
import { loggedIn } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { playersReturn, summaryReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";
import { routes as appRoutes } from "./router";

/** What a browser throws when a dynamically imported chunk is gone (e.g. after a deploy). */
const chunkError = new TypeError(
  "Failed to fetch dynamically imported module: /assets/SquadPage.js",
);

/** The app's route table, with /app/squad's lazy import rejecting. */
function withFailingSquadChunk(routes: RouteObject[]): RouteObject[] {
  return routes.map((route) => {
    if (route.path === "squad") {
      return { ...route, lazy: () => Promise.reject(chunkError) };
    }
    return route.children
      ? ({ ...route, children: withFailingSquadChunk(route.children) } as RouteObject)
      : route;
  });
}

const nav = () => screen.getByRole("navigation", { name: he.shell.navLabel });

let consoleError: MockInstance<typeof console.error>;

beforeEach(() => {
  server.use(...loggedIn(), playersReturn([]));
  consoleError = vi.spyOn(console, "error").mockImplementation(() => {});
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/** The error element is up, inside the shell, with the page's own title. */
async function expectErrorInShell() {
  const alert = await screen.findByRole("alert");
  expect(alert).toHaveTextContent(he.routeError.message);
  expect(within(alert).getByRole("button", { name: he.routeError.reload })).toBeInTheDocument();
  expect(screen.getByRole("main")).toContainElement(alert);
  expect(nav()).toBeInTheDocument();
  expect(screen.getByRole("banner")).toBeInTheDocument();
  expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(he.nav.squad);
  expect(screen.queryByText(/Unexpected Application Error/)).toBeNull();
}

describe("a page whose code fails to load", () => {
  it("shows the error inside the shell on a page load, and logs the error", async () => {
    renderWithProviders({
      initialEntries: ["/app/squad"],
      routes: withFailingSquadChunk(appRoutes),
    });

    await expectErrorInShell();
    expect(consoleError).toHaveBeenCalledWith(expect.any(String), chunkError);
  });

  it("shows the error inside the shell when navigated to", async () => {
    server.use(summaryReturns());
    const { router } = renderWithProviders({
      initialEntries: ["/app"],
      routes: withFailingSquadChunk(appRoutes),
    });
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });

    fireEvent.click(within(nav()).getByRole("link", { name: he.nav.squad }));

    await expectErrorInShell();
    expect(router.state.location.pathname).toBe("/app/squad");
    expect(within(nav()).getByRole("link", { name: he.nav.squad })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });

  it("reloads the page from its button", async () => {
    renderWithProviders({
      initialEntries: ["/app/squad"],
      routes: withFailingSquadChunk(appRoutes),
    });
    await screen.findByRole("alert");
    const reload = vi.fn();
    // jsdom's location.reload can't be redefined, but the location object can be replaced.
    vi.stubGlobal("location", { ...window.location, reload });

    fireEvent.click(screen.getByRole("button", { name: he.routeError.reload }));

    expect(reload).toHaveBeenCalledTimes(1);
  });

  it("leaves the other pages working", async () => {
    server.use(summaryReturns());
    const { router } = renderWithProviders({
      initialEntries: ["/app/squad"],
      routes: withFailingSquadChunk(appRoutes),
    });
    await screen.findByRole("alert");

    fireEvent.click(within(nav()).getByRole("link", { name: he.nav.dashboard }));

    expect(
      await screen.findByRole("heading", { level: 1, name: he.nav.dashboard }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(router.state.location.pathname).toBe("/app");
    // The dashboard itself, not just the shell's title.
    expect(
      await screen.findByRole("heading", { level: 2, name: he.dashboard.lines.title }),
    ).toBeInTheDocument();
  });
});
