import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { loggedIn } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

describe("routes", () => {
  // /app needs a session: the app-load refresh succeeds and /me answers. The not-found page
  // outside /app never asks for either (an unexpected request would fail the test).
  beforeEach(() => {
    server.use(...loggedIn());
  });

  it("redirects / to /app", async () => {
    const { router } = renderWithProviders({ initialEntries: ["/"] });

    expect(await screen.findByRole("heading", { name: he.nav.dashboard })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app");
  });

  it.each([
    ["/app", he.nav.dashboard, he.dashboard.stub],
    ["/app/squad", he.nav.squad, he.squad.stub],
  ])("renders %s inside the shell", async (path, title, stub) => {
    renderWithProviders({ initialEntries: [path] });

    expect(await screen.findByRole("heading", { level: 1, name: title })).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(screen.getByRole("navigation", { name: he.shell.navLabel })).toBeInTheDocument();
    expect(within(screen.getByRole("main")).getByText(stub)).toBeInTheDocument();
  });

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

  it.each(["/app/nope", "/app/squad/x"])(
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
    renderWithProviders({ initialEntries: ["/app"] });

    await screen.findByRole("heading", { name: he.nav.dashboard });
    expect(document.documentElement).toHaveAttribute("dir", "rtl");
    expect(document.querySelectorAll("[dir]:not([dir='ltr']):not(html)")).toHaveLength(0);
  });
});
