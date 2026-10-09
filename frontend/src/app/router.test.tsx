import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { loggedIn } from "@/test/msw/auth";
import { playerBody, playerReturns, playersReturn, summaryReturns } from "@/test/msw/squad";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

describe("routes", () => {
  // /app needs a session: the app-load refresh succeeds and /me answers. The not-found page
  // outside /app never asks for either (an unexpected request would fail the test).
  beforeEach(() => {
    server.use(...loggedIn());
  });

  it("redirects / to /app", async () => {
    server.use(summaryReturns());
    const { router } = renderWithProviders({ initialEntries: ["/"] });

    expect(await screen.findByRole("heading", { name: he.nav.dashboard })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app");
    // Back must not return to /.
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("renders /app inside the shell", async () => {
    server.use(summaryReturns());
    renderWithProviders({ initialEntries: ["/app"] });

    expect(
      await screen.findByRole("heading", { level: 1, name: he.nav.dashboard }),
    ).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(screen.getByRole("navigation", { name: he.shell.navLabel })).toBeInTheDocument();
    expect(
      await within(screen.getByRole("main")).findByRole("heading", {
        level: 2,
        name: he.dashboard.lines.title,
      }),
    ).toBeInTheDocument();
  });

  // React Router ranks a static segment above a dynamic one, so "new" is never a :playerId,
  // and /edit is its own, deeper route.
  it.each([
    ["/app/squad/new", "squad/new", {}, he.squad.addPlayer, he.squad.form.submitCreate],
    ["/app/squad/p1", "squad/:playerId", { playerId: "p1" }, he.squad.playerCard, null],
    [
      "/app/squad/p1/edit",
      "squad/:playerId/edit",
      { playerId: "p1" },
      he.squad.editPlayer,
      he.squad.form.submitEdit,
    ],
    ["/app/squad/x/edit", "squad/:playerId/edit", { playerId: "x" }, he.squad.editPlayer, null],
  ])(
    "matches %s to the route %s, inside the shell with סגל active",
    async (path, routePath, params, title, submit) => {
      server.use(playerReturns(playerBody()));
      const { router } = renderWithProviders({ initialEntries: [path] });

      expect(await screen.findByRole("heading", { level: 1, name: title })).toBeInTheDocument();
      expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
      const deepest = router.state.matches[router.state.matches.length - 1];
      expect(deepest.route.path).toBe(routePath);
      expect(deepest.params).toEqual(params);
      const nav = screen.getByRole("navigation", { name: he.shell.navLabel });
      expect(within(nav).getByRole("link", { name: he.nav.squad })).toHaveAttribute(
        "aria-current",
        "page",
      );
      expect(document.title).toBe(`${title} · SquadPulse`);
      if (submit !== null) {
        expect(await screen.findByRole("button", { name: submit })).toBeInTheDocument();
      }
    },
  );

  it.each(["/nope", "/authors"])(
    "renders the full-page not-found, without the shell, at %s",
    async (path) => {
      renderWithProviders({ initialEntries: [path] });

      expect(await screen.findByRole("heading", { name: he.notFound.title })).toBeInTheDocument();
      expect(screen.getByRole("link", { name: he.notFound.backToApp })).toHaveAttribute(
        "href",
        "/app",
      );
      expect(screen.queryByRole("navigation")).toBeNull();
    },
  );

  it("renders the squad table at /app/squad", async () => {
    server.use(playersReturn([]));
    renderWithProviders({ initialEntries: ["/app/squad"] });

    expect(
      await screen.findByRole("heading", { level: 1, name: he.nav.squad }),
    ).toBeInTheDocument();
    expect(await screen.findByText(he.squad.empty.active)).toBeInTheDocument();
  });

  it.each(["/app/nope", "/app/squad/p1/x"])(
    "renders the not-found page inside the shell at %s",
    async (path) => {
      renderWithProviders({ initialEntries: [path] });

      expect(
        await screen.findByRole("heading", { level: 1, name: he.notFound.title }),
      ).toBeInTheDocument();
      expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
      expect(screen.getByRole("navigation", { name: he.shell.navLabel })).toBeInTheDocument();
      const main = screen.getByRole("main");
      expect(within(main).getByText(he.notFound.description)).toBeInTheDocument();
      expect(within(main).getByRole("link", { name: he.notFound.backToApp })).toHaveAttribute(
        "href",
        "/app",
      );
    },
  );

  it("doesn't override the document's direction", async () => {
    // index.html sets <html lang="he" dir="rtl">, but Vitest doesn't load index.html, so set it
    // here as the page would and check that rendering the app leaves it alone.
    document.documentElement.setAttribute("dir", "rtl");
    server.use(summaryReturns());
    renderWithProviders({ initialEntries: ["/app"] });

    await screen.findByRole("heading", { name: he.nav.dashboard });
    expect(document.documentElement).toHaveAttribute("dir", "rtl");
    expect(document.querySelectorAll("[dir]:not([dir='ltr']):not(html)")).toHaveLength(0);
  });
});
