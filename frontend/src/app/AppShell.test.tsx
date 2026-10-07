import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, Title } from "@/lib/auth/currentUser";
import { deferred } from "@/test/deferred";
import {
  apiError,
  currentUserBody,
  imageReturns,
  meReturns,
  refreshRefused,
  refreshReturns,
} from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { playersReturn, summaryReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

const LOGO = "/clubs/me/logo";
const PHOTO = "/users/me/photo";

/**
 * Logged in at `path`, with /me answering currentUserBody(user), an empty squad and the dashboard's
 * summary. Waits for the shell's <h1>.
 */
async function renderShell(path = "/app", user: Partial<CurrentUser> = {}) {
  server.use(refreshReturns("t1"), meReturns(user), playersReturn([]), summaryReturns());
  const rendered = renderWithProviders({ initialEntries: [path] });
  await screen.findByRole("heading", { level: 1 });
  return rendered;
}

const nav = () => screen.getByRole("navigation", { name: he.shell.navLabel });
const navLink = (name: string) => within(nav()).getByRole("link", { name });
const images = () => [...document.querySelectorAll("img")].map((img) => img.getAttribute("src"));

let created: string[];
let fetchSpy: MockInstance<typeof fetch>;

/**
 * The paths fetched so far. An unhandled request alone wouldn't fail these tests: MSW rejects
 * it, the image hook reports an error, and the fallback looks just like "no image". apiFetch
 * calls fetch synchronously from the image's effect, so once the shell is on screen any image
 * request has been made.
 */
const fetchedPaths = () =>
  fetchSpy.mock.calls.map(([input]) => new URL(String(input), "http://localhost").pathname);

beforeEach(() => {
  created = [];
  fetchSpy = vi.spyOn(globalThis, "fetch");
  vi.spyOn(URL, "createObjectURL").mockImplementation(() => {
    const url = `blob:test/${created.length + 1}`;
    created.push(url);
    return url;
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
  document.title = "SquadPulse";
  return () => vi.restoreAllMocks();
});

describe("the app shell", () => {
  it("shows the club, the navigation, the user and the page title at /app", async () => {
    await renderShell("/app", { fullName: "Dana Cohen", title: "HEAD_COACH" });

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(he.nav.dashboard);
    expect(nav()).toBeInTheDocument();
    expect(screen.getByText("Test FC")).toHaveAttribute("title", "Test FC");
    expect(screen.getByText("Dana Cohen")).toHaveAttribute("title", "Dana Cohen");
    expect(screen.getByText(he.titles.HEAD_COACH)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: he.shell.logout })).toBeEnabled();
    expect(screen.getByRole("img", { name: he.app.name })).toBeInTheDocument();
  });

  it("puts the sidebar before the content in the DOM (the start side in RTL)", async () => {
    await renderShell();

    const order = nav().compareDocumentPosition(screen.getByRole("main"));
    expect(order & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole("banner").compareDocumentPosition(screen.getByRole("main"))).toBe(
      Node.DOCUMENT_POSITION_FOLLOWING,
    );
  });

  it("isn't rendered on the login screen", async () => {
    server.use(refreshRefused());
    renderWithProviders({ initialEntries: ["/app/login"] });

    await screen.findByRole("heading", { name: he.auth.login.title });
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(screen.queryByRole("banner")).toBeNull();
  });

  it("isn't rendered while /me is loading", async () => {
    const gate = deferred();
    server.use(
      refreshReturns("t1"),
      http.get("/auth/users/me", async () => {
        await gate.promise;
        return HttpResponse.json(currentUserBody());
      }),
      summaryReturns(),
    );
    renderWithProviders({ initialEntries: ["/app"] });

    expect(await screen.findByRole("status")).toHaveTextContent(he.auth.loading);
    expect(screen.queryByRole("navigation")).toBeNull();
    gate.resolve();
    expect(await screen.findByRole("navigation")).toBeInTheDocument();
  });

  it.each(Object.entries(he.titles) as [Title, string][])(
    "shows the title %s in Hebrew",
    async (title, hebrew) => {
      await renderShell("/app", { title });

      expect(screen.getByText(hebrew)).toBeInTheDocument();
    },
  );

  it("translates exactly the six titles the backend has", () => {
    expect(Object.keys(he.titles).sort()).toEqual(
      [
        "ANALYST",
        "ASSISTANT_COACH",
        "CLUB_MANAGER",
        "FITNESS_COACH",
        "GOALKEEPING_COACH",
        "HEAD_COACH",
      ].sort(),
    );
  });
});

describe("the club logo and the user photo", () => {
  it("show initials, without requesting an image, when there's no logo or photo", async () => {
    await renderShell("/app", {
      fullName: "Dana Cohen",
      hasPhoto: false,
      club: { id: "c1", name: "Test FC", hasLogo: false },
    });

    // The dashboard's own request, and no image.
    expect(fetchedPaths()).toEqual(["/auth/refresh", "/auth/users/me", "/squad/summary"]);
    expect(screen.getByText("TF")).toHaveAttribute("aria-hidden", "true");
    expect(screen.getByText("DC")).toHaveAttribute("aria-hidden", "true");
    expect(images()).toEqual([]);
  });

  it("show the images when there are a logo and a photo", async () => {
    server.use(imageReturns(LOGO), imageReturns(PHOTO));
    await renderShell("/app", {
      hasPhoto: true,
      club: { id: "c1", name: "Test FC", hasLogo: true },
    });

    await waitFor(() => expect(images()).toHaveLength(2));
    expect(images().sort()).toEqual(["blob:test/1", "blob:test/2"]);
    expect(
      fetchedPaths()
        .filter((path) => path === LOGO || path === PHOTO)
        .sort(),
    ).toEqual([LOGO, PHOTO]);
    for (const img of document.querySelectorAll("img")) {
      expect(img).toHaveAttribute("alt", "");
    }
    expect(screen.queryByText("TF")).toBeNull();
    expect(screen.queryByText("DC")).toBeNull();
  });

  it("show initials while the images are loading", async () => {
    const gate = deferred();
    server.use(imageReturns(LOGO, gate.promise), imageReturns(PHOTO, gate.promise));
    await renderShell("/app", {
      hasPhoto: true,
      club: { id: "c1", name: "Test FC", hasLogo: true },
    });

    expect(screen.getByText("TF")).toBeInTheDocument();
    expect(screen.getByText("DC")).toBeInTheDocument();
    expect(images()).toEqual([]);

    gate.resolve();
    await waitFor(() => expect(images()).toHaveLength(2));
  });

  it("stay on initials when the images are missing (404)", async () => {
    const answered = { count: 0 };
    const missing = () => {
      answered.count++;
      return apiError(404, "Not Found", "No image");
    };
    server.use(http.get(LOGO, missing), http.get(PHOTO, missing));
    await renderShell("/app", {
      hasPhoto: true,
      club: { id: "c1", name: "Test FC", hasLogo: true },
    });

    await waitFor(() => expect(answered.count).toBe(2));
    expect(screen.getByText("TF")).toBeInTheDocument();
    expect(screen.getByText("DC")).toBeInTheDocument();
    expect(images()).toEqual([]);
  });

  it("stay on initials, with no error shown, when the images fail (500)", async () => {
    const answered = { count: 0 };
    const failing = () => {
      answered.count++;
      return apiError(500, "Internal Server Error", "Unexpected error");
    };
    server.use(http.get(LOGO, failing), http.get(PHOTO, failing));
    await renderShell("/app", {
      hasPhoto: true,
      club: { id: "c1", name: "Test FC", hasLogo: true },
    });

    await waitFor(() => expect(answered.count).toBe(2));
    expect(screen.getByText("TF")).toBeInTheDocument();
    expect(screen.getByText("DC")).toBeInTheDocument();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(he.nav.dashboard);
  });

  it("show an icon when the name has no letters", async () => {
    await renderShell("/app", {
      fullName: "007",
      club: { id: "c1", name: "1948", hasLogo: false },
    });

    const clubFallback = document.querySelector("svg.lucide-shield")?.parentElement;
    const userFallback = document.querySelector("svg.lucide-user")?.parentElement;
    expect(clubFallback).toHaveAttribute("aria-hidden", "true");
    expect(clubFallback).toHaveTextContent(/^$/);
    expect(userFallback).toHaveAttribute("aria-hidden", "true");
    expect(userFallback).toHaveTextContent(/^$/);
  });
});

describe("navigation", () => {
  it("marks the current page and moves with a click", async () => {
    const { router } = await renderShell("/app");

    expect(navLink(he.nav.dashboard)).toHaveAttribute("aria-current", "page");
    expect(navLink(he.nav.squad)).not.toHaveAttribute("aria-current");

    fireEvent.click(navLink(he.nav.squad));

    expect(
      await screen.findByRole("heading", { level: 1, name: he.nav.squad }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app/squad");
    expect(navLink(he.nav.squad)).toHaveAttribute("aria-current", "page");
    expect(navLink(he.nav.dashboard)).not.toHaveAttribute("aria-current");
    expect(
      await within(screen.getByRole("main")).findByText(he.squad.empty.active),
    ).toBeInTheDocument();
  });

  it("keeps the squad link current below /app/squad", async () => {
    await renderShell("/app/squad/x");

    expect(navLink(he.nav.squad)).toHaveAttribute("aria-current", "page");
    expect(navLink(he.nav.dashboard)).not.toHaveAttribute("aria-current");
  });

  it("marks no page current on the not-found page", async () => {
    await renderShell("/app/nope");

    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(he.notFound.title);
    expect(within(nav()).queryByRole("link", { current: "page" })).toBeNull();
  });

  it("links only to the live pages", async () => {
    await renderShell();

    expect(
      within(nav())
        .getAllByRole("link")
        .map((link) => [link.textContent, link.getAttribute("href")]),
    ).toEqual([
      [he.nav.dashboard, "/app"],
      [he.nav.squad, "/app/squad"],
    ]);
  });
});

describe("the coming-soon group", () => {
  const items = Object.entries(he.shell.comingSoon)
    .filter(([key]) => key !== "label")
    .map(([, text]) => text);

  it("lists the four sections, labelled as coming soon, outside the navigation", async () => {
    await renderShell();

    const list = screen.getByRole("list", { name: he.shell.comingSoon.label });
    expect(
      within(list)
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual([
      he.shell.comingSoon.schedule,
      he.shell.comingSoon.training,
      he.shell.comingSoon.matches,
      he.shell.comingSoon.tactics,
    ]);
    expect(nav()).not.toContainElement(list);
  });

  it.each(items)("shows %s as text: not a link or a button, not focusable", async (text) => {
    await renderShell();

    const item = screen.getByText(text);
    expect(screen.queryByRole("link", { name: text })).toBeNull();
    expect(screen.queryByRole("button", { name: text })).toBeNull();
    expect(item.closest("a, button, [tabindex], [href]")).toBeNull();
    expect(item.querySelector("a, button, [tabindex], [href]")).toBeNull();
    item.focus();
    expect(document.activeElement).not.toBe(item);
  });
});

describe("the page title", () => {
  it("names the page in the browser tab, and follows navigation", async () => {
    await renderShell("/app");
    expect(document.title).toBe(`${he.nav.dashboard} · SquadPulse`);

    fireEvent.click(navLink(he.nav.squad));

    await screen.findByRole("heading", { level: 1, name: he.nav.squad });
    expect(document.title).toBe(`${he.nav.squad} · SquadPulse`);
  });

  it("is the not-found title on an unknown /app path", async () => {
    await renderShell("/app/nope");

    expect(document.title).toBe(`${he.notFound.title} · SquadPulse`);
  });
});

describe("logout", () => {
  it("sends one POST /auth/logout, disables the button meanwhile, and lands on login without next", async () => {
    const gate = deferred();
    const logout = { count: 0 };
    server.use(
      http.post("/auth/logout", async () => {
        logout.count++;
        await gate.promise;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    const { router, queryClient } = await renderShell("/app/squad");
    expect(screen.getAllByRole("button", { name: he.shell.logout })).toHaveLength(1);
    expect(document.title).toBe(`${he.nav.squad} · SquadPulse`);

    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));
    await waitFor(() => expect(logout.count).toBe(1));
    expect(screen.getByRole("button", { name: he.shell.logout })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));
    gate.resolve();

    expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
    expect(router.state.location.pathname + router.state.location.search).toBe("/app/login");
    expect(logout.count).toBe(1);
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
    expect(screen.queryByRole("navigation")).toBeNull();
    expect(document.title).toBe("SquadPulse");
  });
});
