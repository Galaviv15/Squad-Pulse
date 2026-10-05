import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { renderWithProviders } from "@/test/render";

describe("routes", () => {
  it("redirects / to /app", async () => {
    const { router } = renderWithProviders({ initialEntries: ["/"] });

    expect(await screen.findByRole("heading", { name: he.app.name })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app");
  });

  it("renders the placeholder at /app", async () => {
    renderWithProviders({ initialEntries: ["/app"] });

    expect(await screen.findByRole("heading", { name: he.app.name })).toBeInTheDocument();
    expect(screen.getByText(he.app.welcome)).toBeInTheDocument();
    expect(screen.getByText(he.app.phaseNote)).toBeInTheDocument();
  });

  it("shows the position code badge as an LTR island", async () => {
    renderWithProviders({ initialEntries: ["/app"] });

    expect(await screen.findByText("CB")).toHaveAttribute("dir", "ltr");
  });

  it.each(["/nope", "/app/nope", "/authors"])("renders the not-found page at %s", async (path) => {
    renderWithProviders({ initialEntries: [path] });

    expect(await screen.findByRole("heading", { name: he.notFound.title })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: he.notFound.backToApp })).toHaveAttribute(
      "href",
      "/app",
    );
  });

  it("doesn't override the document's direction", async () => {
    // index.html sets <html lang="he" dir="rtl">, but Vitest doesn't load index.html, so set it
    // here as the page would and check that rendering the app leaves it alone.
    document.documentElement.setAttribute("dir", "rtl");
    renderWithProviders({ initialEntries: ["/app"] });

    await screen.findByRole("heading", { name: he.app.name });
    expect(document.documentElement).toHaveAttribute("dir", "rtl");
    expect(document.querySelectorAll("[dir]:not([dir='ltr']):not(html)")).toHaveLength(0);
  });
});
