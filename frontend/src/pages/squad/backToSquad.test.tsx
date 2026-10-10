import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeAll, describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser } from "@/lib/auth/currentUser";
import { squadReturnPath } from "@/lib/squad/squadReturnPath";
import { apiError, loginReturns, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import {
  deleteReturns,
  playerBody,
  playerReturns,
  recordedPlayers,
  releaseReturns,
  summaryReturns,
  updatePlayerReturns,
} from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([
    import("@/pages/squad/SquadPage"),
    import("@/pages/squad/PlayerCardPage"),
    import("@/pages/squad/NewPlayerPage"),
    import("@/pages/squad/EditPlayerPage"),
  ]),
);

/** The squad's normalized URL these tests filter to: a position, in the cards view. */
const FILTERED = "/app/squad?position=CB&view=cards";

type Router = ReturnType<typeof renderWithProviders>["router"];

const url = (router: Router) => router.state.location.pathname + router.state.location.search;

const backLink = () => screen.getByRole("link", { name: he.squad.backToSquad });

/**
 * Logged in as `user`, at `path` (after `entries`, earlier history), with the squad list
 * (recording its queries) and player p1.
 */
function renderAt(
  path: string,
  { user = {}, entries = [] }: { user?: Partial<CurrentUser>; entries?: string[] } = {},
) {
  const list = recordedPlayers([playerBody()]);
  server.use(refreshReturns("t1"), meReturns(user), list.handler, playerReturns(playerBody()));
  return {
    ...renderWithProviders({ initialEntries: [...entries, path] }),
    queries: list.queries,
  };
}

/** Waits for the squad page to show its list, as cards. */
async function squadCardsShown() {
  await screen.findByRole("radiogroup", { name: he.squad.statusLabel });
  await waitFor(() => expect(screen.queryByText(he.squad.loading)).toBeNull());
  await within(screen.getByRole("main")).findByRole("list");
}

/** Waits for player p1's card. */
const cardShown = () => screen.findByRole("heading", { level: 2, name: "Yossi Levi" });

/** Visits the filtered squad (cards), then goes to `to` in the same router: the squad is remembered. */
async function fromFilteredSquadTo(to: string, user: Partial<CurrentUser> = {}) {
  const rendered = renderAt(FILTERED, { user });
  await squadCardsShown();
  await act(() => rendered.router.navigate(to));
  return rendered;
}

/** Opens a filter select and chooses an option as a mouse does (Base UI: pointerdown + click). */
async function choose(label: string, option: string) {
  fireEvent.click(screen.getByRole("combobox", { name: label }));
  const item = await screen.findByRole("option", { name: option });
  fireEvent.pointerDown(item, { pointerType: "mouse" });
  fireEvent.click(item);
  await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
}

describe("'חזרה לסגל' from the player card", () => {
  it("returns to the filters and the cards view chosen on the squad, and the list query has no view", async () => {
    const { router, queries } = renderAt("/app/squad");
    await screen.findByRole("radiogroup", { name: he.squad.statusLabel });
    await choose(he.squad.filters.position, "CB");
    fireEvent.click(screen.getByRole("radio", { name: he.squad.views.cards }));
    await squadCardsShown();
    expect(url(router)).toBe(FILTERED);

    // Open the player from their card.
    fireEvent.click(screen.getByRole("link", { name: "Yossi Levi" }));
    await cardShown();
    expect(backLink()).toHaveAttribute("href", FILTERED);
    fireEvent.click(backLink());

    await squadCardsShown();
    expect(url(router)).toBe(FILTERED);
    expect(screen.getByRole("radio", { name: he.squad.views.cards })).toBeChecked();
    await waitFor(() => expect(queries.at(-1)).toBe("position=CB"));
    expect(queries.some((query) => query.includes("view"))).toBe(false);
  });

  it("returns to the normalized URL after a visit to a non-normalized one, never the raw query", async () => {
    const raw =
      "/app/squad?view=cards&foo=1&position=CB&status=active&medicalStatus=XX&minAge=40&maxAge=20";
    const { router, queries } = renderAt(raw);
    await squadCardsShown();
    await waitFor(() => expect(url(router)).toBe(FILTERED));
    expect(squadReturnPath()).toBe(FILTERED);

    fireEvent.click(screen.getByRole("link", { name: "Yossi Levi" }));
    await cardShown();
    fireEvent.click(backLink());

    await squadCardsShown();
    expect(url(router)).toBe(FILTERED);
    expect(queries.every((query) => query === "position=CB")).toBe(true);
  });

  it("goes to the bare squad when the squad wasn't visited in this tab", async () => {
    const { router } = renderAt("/app/squad/p1");
    await cardShown();
    expect(backLink()).toHaveAttribute("href", "/app/squad");

    fireEvent.click(backLink());

    await screen.findByRole("radiogroup", { name: he.squad.statusLabel });
    expect(url(router)).toBe("/app/squad");
  });

  it("goes to the bare squad after the session ended and a new login", async () => {
    const { router } = renderAt(FILTERED);
    await squadCardsShown();
    server.use(
      http.post("/auth/logout", () => new HttpResponse(null, { status: 204 })),
      loginReturns("t2"),
      summaryReturns(),
    );

    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));
    await screen.findByRole("heading", { name: he.auth.login.title });
    fireEvent.change(screen.getByLabelText(he.auth.fields.email), {
      target: { value: "coach@example.com" },
    });
    fireEvent.change(screen.getByLabelText(he.auth.fields.password), { target: { value: "pw" } });
    fireEvent.click(screen.getByRole("button", { name: he.auth.login.submit }));
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
    await screen.findByText(he.dashboard.toSquadTable);
    await act(() => router.navigate("/app/squad/p1"));
    await cardShown();

    expect(backLink()).toHaveAttribute("href", "/app/squad");
  });
});

describe("the card's delete", () => {
  it("returns to the filtered cards view with the notice once, and leaves no card in history", async () => {
    const { router } = renderAt(FILTERED, { entries: ["/app"] });
    await squadCardsShown();
    // Back to /app below shows the dashboard, which requests the squad summary.
    server.use(deleteReturns("p1").handler, summaryReturns());
    fireEvent.click(screen.getByRole("link", { name: "Yossi Levi" }));
    await cardShown();

    fireEvent.click(screen.getByRole("button", { name: he.squad.actions.delete }));
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.delete }));

    expect(await screen.findByText("Yossi Levi נמחק לצמיתות.")).toBeInTheDocument();
    expect(url(router)).toBe(FILTERED);
    expect(screen.getByRole("radio", { name: he.squad.views.cards })).toBeChecked();
    // The notice's state is cleared from the entry, the filters and the view stay.
    await waitFor(() => expect(router.state.location.state).toBeNull());
    expect(url(router)).toBe(FILTERED);

    // The card was replaced: Back goes to the squad entry the card was opened from, then to /app.
    // (Between those two squad entries the page stays mounted, so its notice does too.)
    await act(() => router.navigate(-1));
    expect(url(router)).toBe(FILTERED);
    await act(() => router.navigate(-1));
    expect(url(router)).toBe("/app");
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
  });
});

describe("the other 'back to the squad' links go to the last squad URL", () => {
  it("the player card's 404", async () => {
    await fromFilteredSquadTo("/app/squad/gone");

    expect(await screen.findByText(he.squad.player.notFound)).toBeInTheDocument();
    expect(backLink()).toHaveAttribute("href", FILTERED);
  });

  it("the 'no permission' message", async () => {
    await fromFilteredSquadTo("/app/squad/new", { permissionLevel: "VIEW_ONLY" });

    expect(await screen.findByText(he.squad.noPermission)).toBeInTheDocument();
    expect(backLink()).toHaveAttribute("href", FILTERED);
  });

  it("a card dialog's 404", async () => {
    await fromFilteredSquadTo("/app/squad/p1");
    await cardShown();
    server.use(
      releaseReturns(playerBody(), () => apiError(404, "Not Found", "Player not found")).handler,
    );
    fireEvent.click(screen.getByRole("button", { name: he.squad.actions.release }));
    const dialog = await screen.findByRole("alertdialog");

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.release }));

    const alert = await within(dialog).findByRole("alert");
    expect(within(alert).getByRole("link", { name: he.squad.backToSquad })).toHaveAttribute(
      "href",
      FILTERED,
    );
  });

  it("the edit form's 'not found' alert", async () => {
    await fromFilteredSquadTo("/app/squad/p1/edit");
    server.use(
      updatePlayerReturns("p1", () => apiError(404, "Not Found", "Player not found")).handler,
    );
    // The form shows once the player has loaded, filled from it.
    const submit = await screen.findByRole("button", { name: he.squad.form.submitEdit });

    fireEvent.click(submit);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(he.squad.player.notFound);
    expect(within(alert).getByRole("link", { name: he.squad.backToSquad })).toHaveAttribute(
      "href",
      FILTERED,
    );
  });

  it("the add form's 'ביטול'", async () => {
    await fromFilteredSquadTo("/app/squad/new");

    const cancel = await screen.findByRole("link", { name: he.squad.form.cancel });
    expect(cancel).toHaveAttribute("href", FILTERED);
  });
});

describe("the navigation entry points stay the bare squad", () => {
  it("the dashboard's 'לטבלת הסגל' and the sidebar's 'סגל'", async () => {
    server.use(summaryReturns());
    await fromFilteredSquadTo("/app");
    expect(squadReturnPath()).toBe(FILTERED);

    const toTable = await screen.findByRole("link", { name: he.dashboard.toSquadTable });
    expect(toTable).toHaveAttribute("href", "/app/squad");
    const nav = screen.getByRole("navigation", { name: he.shell.navLabel });
    expect(within(nav).getByRole("link", { name: he.nav.squad })).toHaveAttribute(
      "href",
      "/app/squad",
    );
  });
});
