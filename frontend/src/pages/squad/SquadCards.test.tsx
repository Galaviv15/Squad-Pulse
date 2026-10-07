import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
  type MockInstance,
} from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, PermissionLevel } from "@/lib/auth/currentUser";
import { ageOn } from "@/lib/squad/age";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { imageReturns, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { playerBody, playerReturns, recordedPlayers, releaseReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

// Load the lazy page's code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() => import("@/pages/squad/SquadPage"));

const LIST = "/squad/players";
const photo = (id: string) => `/squad/players/${id}/photo`;

let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
  let created = 0;
  vi.spyOn(URL, "createObjectURL").mockImplementation(() => `blob:test/${++created}`);
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** The paths fetched so far (an unexpected request only rejects its fetch: record them). */
const fetchedPaths = () =>
  fetchSpy.mock.calls.map(([input]) => new URL(String(input), "http://localhost").pathname);
const listRequests = () => fetchedPaths().filter((path) => path === LIST).length;

/**
 * Logged in at `path`, the list answering `players` and recording each request's query string.
 * Waits for the first list to be shown.
 */
async function renderSquad(
  path = "/app/squad?view=cards",
  { players = [playerBody()], user = {} }: { players?: Player[]; user?: Partial<CurrentUser> } = {},
) {
  const list = recordedPlayers(players);
  server.use(refreshReturns("t1"), meReturns(user), list.handler);
  const rendered = renderWithProviders({ initialEntries: [path] });
  await screen.findByRole("heading", { level: 1, name: he.nav.squad });
  await waitFor(() => expect(screen.queryByText(he.squad.loading)).toBeNull());
  return { ...rendered, queries: list.queries };
}

const search = (router: { state: { location: { search: string } } }) =>
  router.state.location.search;

const viewRadio = (name: string) => screen.getByRole("radio", { name });
const listRadio = () => viewRadio(he.squad.views.list);
const cardsRadio = () => viewRadio(he.squad.views.cards);
const statusRadio = (name: string) => screen.getByRole("radio", { name });

/** The cards grid: the one list inside <main> (the shell's lists are in the sidebar). */
const grid = () => within(screen.getByRole("main")).getByRole("list");
const queryGrid = () => within(screen.getByRole("main")).queryByRole("list");
const cards = () => within(grid()).getAllByRole("listitem");
const cardOf = (name: string) => screen.getByRole("link", { name }).closest("li")!;
const trigger = (name: string) =>
  screen.getByRole("button", { name: he.squad.actions.menu.replace("{{name}}", name) });

/** Opens a filter select and chooses an option as a mouse does (Base UI: pointerdown + click). */
async function choose(label: string, option: string) {
  fireEvent.click(screen.getByRole("combobox", { name: label }));
  const item = await screen.findByRole("option", { name: option });
  fireEvent.pointerDown(item, { pointerType: "mouse" });
  fireEvent.click(item);
  await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
}

/** The names in a `name`'s actions menu, opened by mouse, then closed with Escape. */
async function menuItems(name: string) {
  fireEvent.click(trigger(name));
  const menu = await screen.findByRole("menu");
  const items = within(menu)
    .getAllByRole("menuitem")
    .map((item) => item.textContent);
  fireEvent.keyDown(menu, { key: "Escape" });
  await waitFor(() => expect(screen.queryByRole("menu")).toBeNull());
  return items;
}

/** Whether `element` or one of its ancestors up to (and including) `card` is dimmed. */
function dimmedWithin(element: Element, card: Element) {
  for (let node: Element | null = element; node !== null; node = node.parentElement) {
    if (node.classList.contains("opacity-60")) {
      return true;
    }
    if (node === card) {
      return false;
    }
  }
  return false;
}

describe("the view toggle", () => {
  it("is a radio group named תצוגה: רשימה then כרטיסיות, named, with tooltips, one tab stop", async () => {
    await renderSquad("/app/squad");

    const group = screen.getByRole("radiogroup", { name: he.squad.viewLabel });
    const radios = within(group).getAllByRole("radio");
    expect(radios).toEqual([listRadio(), cardsRadio()]);
    expect(radios.map((radio) => radio.getAttribute("title"))).toEqual([
      he.squad.views.list,
      he.squad.views.cards,
    ]);
    expect(radios.map((radio) => radio.getAttribute("tabindex"))).toEqual(["0", "-1"]);
    expect(listRadio()).toHaveAttribute("aria-checked", "true");
  });

  it("switches with the arrow keys, in the reading direction (RTL: left is forward)", async () => {
    const { router } = await renderSquad("/app/squad");
    listRadio().focus();

    fireEvent.keyDown(listRadio(), { key: "ArrowLeft" });

    await waitFor(() => expect(cardsRadio()).toHaveAttribute("aria-checked", "true"));
    expect(cardsRadio()).toHaveFocus();
    expect(search(router)).toBe("?view=cards");
    expect(grid()).toBeInTheDocument();

    fireEvent.keyDown(cardsRadio(), { key: "ArrowRight" });

    await waitFor(() => expect(listRadio()).toHaveAttribute("aria-checked", "true"));
    expect(search(router)).toBe("");
    expect(screen.getByRole("table")).toBeInTheDocument();
  });

  it.each(["VIEW_ONLY", "ADMIN"] as PermissionLevel[])(
    "is in the toolbar's end group for %s, before any add-player link",
    async (permissionLevel) => {
      await renderSquad("/app/squad", { user: { permissionLevel } });

      const group = screen.getByRole("radiogroup", { name: he.squad.viewLabel });
      const end = group.parentElement!;
      const add = screen.queryByRole("link", { name: he.squad.addPlayer });
      expect([...end.children]).toEqual(add === null ? [group] : [group, add]);
      expect(end.parentElement!.firstElementChild).toBe(
        screen.getByRole("radiogroup", { name: he.squad.statusLabel }),
      );
    },
  );
});

describe("the view in the URL", () => {
  it("is the list by default, with no view in the URL", async () => {
    const { router, queries } = await renderSquad("/app/squad");

    expect(search(router)).toBe("");
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(queryGrid()).toBeNull();
    expect(queries).toEqual([""]);
  });

  it("is view=cards after switching to the cards, replacing the history entry", async () => {
    const { router } = await renderSquad("/app/squad");

    fireEvent.click(cardsRadio());

    await waitFor(() => expect(search(router)).toBe("?view=cards"));
    expect(router.state.historyAction).toBe("REPLACE");
    expect(screen.queryByRole("table")).toBeNull();
    expect(cards()).toHaveLength(1);
  });

  it("survives a reload: the same URL renders the cards again", async () => {
    const first = await renderSquad("/app/squad");
    fireEvent.click(cardsRadio());
    await waitFor(() => expect(search(first.router)).toBe("?view=cards"));
    const { pathname, search: query } = first.router.state.location;
    first.unmount();

    const { router } = await renderSquad(pathname + query);

    expect(cardsRadio()).toHaveAttribute("aria-checked", "true");
    expect(cards()).toHaveLength(1);
    expect(search(router)).toBe("?view=cards");
  });

  it.each([["view=bogus"], ["view=CARDS"], ["view=list"], ["view=cards&view=cards"]])(
    "treats %s as the list and removes it from the URL",
    async (query) => {
      const { router } = await renderSquad(`/app/squad?${query}`);

      expect(screen.getByRole("table")).toBeInTheDocument();
      await waitFor(() => expect(search(router)).toBe(""));
      expect(router.state.historyAction).toBe("REPLACE");
    },
  );

  it("is normalized after the filters, with them", async () => {
    const { router, queries } = await renderSquad(
      "/app/squad?view=cards&position=CB&status=active&bogus=1",
    );

    await waitFor(() => expect(search(router)).toBe("?position=CB&view=cards"));
    expect(cards()).toHaveLength(1);
    expect(queries).toEqual(["position=CB"]);
  });

  it("with filters renders the filtered cards: the request carries the filters, never the view", async () => {
    const { router, queries } = await renderSquad(
      "/app/squad?status=all&position=GK&minAge=20&view=cards",
      { players: [playerBody({ fullName: "Keeper One", primaryPosition: "GK" })] },
    );

    expect(queries).toEqual(["status=all&position=GK&minAge=20"]);
    expect(within(cardOf("Keeper One")).getByText("GK")).toBeInTheDocument();
    expect(search(router)).toBe("?status=all&position=GK&minAge=20&view=cards");
  });

  it("is kept when the status changes", async () => {
    const { router, queries } = await renderSquad();

    fireEvent.click(statusRadio(he.squad.status.released));

    await waitFor(() => expect(search(router)).toBe("?status=released&view=cards"));
    await waitFor(() => expect(queries.at(-1)).toBe("status=released"));
    expect(grid()).toBeInTheDocument();
  });

  it("is kept when a filter changes", async () => {
    const { router, queries } = await renderSquad();

    await choose(he.squad.filters.position, "CB");

    await waitFor(() => expect(search(router)).toBe("?position=CB&view=cards"));
    await waitFor(() => expect(queries.at(-1)).toBe("position=CB"));
    expect(grid()).toBeInTheDocument();
  });

  it("is kept by ניקוי סינון, which clears the filters (not the status)", async () => {
    const { router, queries } = await renderSquad(
      "/app/squad?status=all&position=GK&preferredFoot=LEFT&view=cards",
    );

    fireEvent.click(screen.getByRole("button", { name: he.squad.filters.clear }));

    await waitFor(() => expect(search(router)).toBe("?status=all&view=cards"));
    await waitFor(() => expect(queries.at(-1)).toBe("status=all"));
    expect(grid()).toBeInTheDocument();
  });

  it("doesn't count as a filter: ניקוי סינון stays disabled with only the view set", async () => {
    await renderSquad();

    expect(screen.getByRole("button", { name: he.squad.filters.clear })).toBeDisabled();
  });

  it("switching back to the list keeps the filters", async () => {
    const { router } = await renderSquad("/app/squad?status=released&position=CB&view=cards");

    fireEvent.click(listRadio());

    await waitFor(() => expect(search(router)).toBe("?status=released&position=CB"));
    expect(screen.getByRole("table")).toBeInTheDocument();
  });
});

describe("switching views", () => {
  it("sends no new list request, either way, and shows the same players in the same order", async () => {
    const players = [
      playerBody({ id: "a", fullName: "Avi Cohen" }),
      playerBody({ id: "b", fullName: "Beni Levi" }),
    ];
    await renderSquad("/app/squad", { players });
    const before = listRequests();
    const tableNames = screen
      .getAllByRole("row")
      .slice(1)
      .map((row) => within(row).getAllByRole("link")[0].textContent);

    fireEvent.click(cardsRadio());
    await waitFor(() => expect(cards()).toHaveLength(2));
    const cardNames = cards().map((card) => within(card).getAllByRole("link")[0].textContent);
    fireEvent.click(listRadio());
    await screen.findByRole("table");

    expect(cardNames).toEqual(tableNames);
    expect(screen.getByText("מוצגים 2 שחקנים")).toBeInTheDocument();
    expect(listRequests()).toBe(before);
  });
});

describe("the cards grid", () => {
  it("is a list of one card per player, in the server's order, without a frame", async () => {
    const players = ["Zed", "Avi", "Moshe"].map((fullName, i) =>
      playerBody({ id: `p${i}`, fullName }),
    );
    await renderSquad(undefined, { players });

    expect(cards().map((card) => within(card).getAllByRole("link")[0].textContent)).toEqual([
      "Zed",
      "Avi",
      "Moshe",
    ]);
    expect(grid().tagName).toBe("UL");
    expect(grid().parentElement).not.toHaveClass("bg-card");
    expect(screen.getByText("מוצגים 3 שחקנים")).toBeInTheDocument();
  });

  it("keeps the previous cards, marked busy, while a new filter's list loads", async () => {
    const gate = deferred();
    server.use(
      refreshReturns("t1"),
      meReturns(),
      http.get(LIST, async ({ request }) => {
        if (new URL(request.url).searchParams.get("status") === "released") {
          await gate.promise;
          return HttpResponse.json([
            playerBody({ id: "r", fullName: "Released One", active: false }),
          ]);
        }
        return HttpResponse.json([playerBody({ fullName: "Active One" })]);
      }),
    );
    renderWithProviders({ initialEntries: ["/app/squad?view=cards"] });
    await screen.findByRole("link", { name: "Active One" });
    expect(grid()).not.toHaveAttribute("aria-busy");

    fireEvent.click(statusRadio(he.squad.status.released));

    await waitFor(() => expect(grid()).toHaveAttribute("aria-busy", "true"));
    expect(grid()).toHaveClass("opacity-60");
    expect(screen.getByRole("link", { name: "Active One" })).toBeInTheDocument();
    expect(screen.queryByText(he.squad.loading)).toBeNull();

    gate.resolve();
    expect(await screen.findByRole("link", { name: "Released One" })).toBeInTheDocument();
    expect(grid()).not.toHaveAttribute("aria-busy");
    expect(grid()).not.toHaveClass("opacity-60");
  });

  it("shows the empty list's message in its frame, as the list does", async () => {
    await renderSquad(undefined, { players: [] });

    const message = screen.getByText(he.squad.empty.active);
    expect(message.closest(".bg-card")).not.toBeNull();
    expect(queryGrid()).toBeNull();
  });
});

describe("a card", () => {
  it("shows every field of a player, a photo requested once, #9 in an LTR island", async () => {
    server.use(imageReturns(photo("p1")));
    await renderSquad(undefined, {
      players: [
        playerBody({
          fullName: "Yossi Levi",
          jerseyNumber: 9,
          primaryPosition: "ST",
          secondaryPosition: "LW",
          dateOfBirth: "1998-05-20",
          heightCm: 182,
          weightKg: 78,
          preferredFoot: "LEFT",
          medicalStatus: "INJURED",
          hasPhoto: true,
        }),
      ],
    });
    const card = cardOf("Yossi Levi");

    const number = within(card).getByText("#9");
    expect(number).toHaveAttribute("dir", "ltr");
    expect(number).toHaveClass("tabular-nums", "text-primary");
    expect(within(card).getByText("ST")).toHaveAttribute("dir", "ltr");
    expect(within(card).getByText("LW")).toHaveAttribute("dir", "ltr");
    const terms = within(card)
      .getAllByRole("term")
      .map((term) => term.textContent);
    const values = within(card)
      .getAllByRole("definition")
      .map((value) => value.textContent);
    expect(terms).toEqual(["גיל", "גובה", "משקל", "רגל"]);
    const age = String(ageOn("1998-05-20", new Date()));
    expect(values).toEqual([age, "182", "78", he.squad.foot.LEFT]);
    expect(within(card).getByText(he.squad.medical.INJURED)).toBeInTheDocument();
    expect(within(card).queryByText(he.squad.released)).toBeNull();
    await waitFor(() => expect(card.querySelector("img")).toHaveAttribute("src", "blob:test/1"));
    expect(fetchedPaths().filter((path) => path === photo("p1"))).toHaveLength(1);
  });

  it("requests no photo for a player without one, showing the initials", async () => {
    await renderSquad(undefined, { players: [playerBody({ fullName: "Yossi Levi" })] });

    const card = cardOf("Yossi Levi");
    expect(within(card).getByText("YL")).toHaveAttribute("aria-hidden", "true");
    expect(card.querySelector("img")).toBeNull();
    expect(fetchedPaths().filter((path) => path.endsWith("/photo"))).toEqual([]);
  });

  it("shows — and no # without a jersey number, and — for every missing stat", async () => {
    await renderSquad(undefined, {
      players: [
        playerBody({
          jerseyNumber: null,
          primaryPosition: null,
          dateOfBirth: null,
          heightCm: null,
          weightKg: null,
          preferredFoot: null,
        }),
      ],
    });
    const card = cardOf("Yossi Levi");

    expect(card.textContent).not.toContain("#");
    expect(
      within(card)
        .getAllByRole("definition")
        .map((value) => value.textContent),
    ).toEqual(["—", "—", "—", "—"]);
    // The jersey number's and the positions' dashes, beside the four stats'.
    expect(within(card).getAllByText("—")).toHaveLength(6);
  });

  it("marks a released player משוחרר and dims the card, all but the actions menu", async () => {
    await renderSquad("/app/squad?status=all&view=cards", {
      players: [
        playerBody({ id: "r", fullName: "Released One", active: false }),
        playerBody({ id: "a", fullName: "Active One" }),
      ],
    });
    const released = cardOf("Released One");
    const active = cardOf("Active One");

    expect(within(released).getByText(he.squad.released)).toBeInTheDocument();
    expect(within(active).queryByText(he.squad.released)).toBeNull();
    for (const part of [
      within(released).getByRole("link", { name: "Released One" }),
      within(released).getAllByRole("definition")[0],
      within(released).getByText(he.squad.medical.FIT),
    ]) {
      expect(dimmedWithin(part, released)).toBe(true);
    }
    expect(dimmedWithin(trigger("Released One"), released)).toBe(false);
    expect(dimmedWithin(within(active).getByRole("link", { name: "Active One" }), active)).toBe(
      false,
    );
  });
});

describe("opening a player from a card", () => {
  beforeEach(() => {
    server.use(playerReturns(playerBody()));
  });

  async function expectPlayerCard(router: { state: { location: { pathname: string } } }) {
    expect(
      await screen.findByRole("heading", { level: 2, name: "Yossi Levi" }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app/squad/p1");
  }

  it("works from the name link", async () => {
    const { router } = await renderSquad();

    fireEvent.click(screen.getByRole("link", { name: "Yossi Levi" }));

    await expectPlayerCard(router);
  });

  it("works from anywhere else on the card, once", async () => {
    const { router } = await renderSquad();
    const navigations: string[] = [];
    router.subscribe((state) => navigations.push(state.location.pathname));

    fireEvent.click(within(cardOf("Yossi Levi")).getAllByRole("definition")[1]);

    await expectPlayerCard(router);
    expect(navigations.filter((path) => path === "/app/squad/p1")).toHaveLength(1);
  });

  it("doesn't happen from the actions trigger or an in-place menu item", async () => {
    const { router } = await renderSquad();

    fireEvent.click(trigger("Yossi Levi"));
    const menu = await screen.findByRole("menu");
    fireEvent.click(within(menu).getByRole("menuitem", { name: he.squad.actions.release }));
    const dialog = await screen.findByRole("alertdialog");
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.dialog.cancel }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

    expect(router.state.location.pathname).toBe("/app/squad");
  });

  it("doesn't happen when the click ends a text selection", async () => {
    const { router } = await renderSquad();
    vi.spyOn(window, "getSelection").mockReturnValue({ toString: () => "Yossi" } as Selection);

    fireEvent.click(within(cardOf("Yossi Levi")).getAllByRole("definition")[1]);

    expect(router.state.location.pathname).toBe("/app/squad");
  });
});

describe("a card's actions menu", () => {
  it.each(["ADMIN", "EDIT_FULL", "VIEW_ONLY"] as PermissionLevel[])(
    "has the table row's items for %s",
    async (permissionLevel) => {
      await renderSquad(undefined, { user: { permissionLevel } });
      const fromCard = await menuItems("Yossi Levi");

      fireEvent.click(listRadio());
      await screen.findByRole("table");
      const fromRow = await menuItems("Yossi Levi");

      expect(fromCard).toEqual(fromRow);
      expect(fromCard[0]).toBe(he.squad.actions.open);
    },
  );

  it("releases a player end to end: dialog, request, the card leaves the active list", async () => {
    const players = [playerBody({ version: 3 }), playerBody({ id: "p2", fullName: "Avi Cohen" })];
    const store = { players };
    server.use(
      refreshReturns("t1"),
      meReturns(),
      http.get(LIST, ({ request }) => {
        const status = new URL(request.url).searchParams.get("status") ?? "active";
        return HttpResponse.json(
          store.players.filter((p) => status === "all" || p.active === (status === "active")),
        );
      }),
    );
    const release = releaseReturns(players[0], () => {
      store.players = [{ ...players[0], active: false, version: 4 }, players[1]];
      return HttpResponse.json(store.players[0]);
    });
    server.use(release.handler);
    const { router } = renderWithProviders({ initialEntries: ["/app/squad?view=cards"] });
    await screen.findByRole("link", { name: "Yossi Levi" });

    fireEvent.click(trigger("Yossi Levi"));
    const menu = await screen.findByRole("menu");
    fireEvent.click(within(menu).getByRole("menuitem", { name: he.squad.actions.release }));
    const dialog = await screen.findByRole("alertdialog");
    expect(dialog).toHaveAccessibleName("שחרור Yossi Levi");
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.release }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(release.bodies).toEqual([{ version: 3 }]);
    await waitFor(() => expect(screen.queryByRole("link", { name: "Yossi Levi" })).toBeNull());
    expect(cards()).toHaveLength(1);
    expect(screen.getByRole("link", { name: "Avi Cohen" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app/squad");
    expect(search(router)).toBe("?view=cards");
    // The opener is gone: focus fell back rather than onto a detached element.
    expect(document.activeElement?.isConnected).toBe(true);
  });
});
