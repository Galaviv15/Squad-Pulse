import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
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
import type { CurrentUser } from "@/lib/auth/currentUser";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import {
  conflict,
  deleteReturns,
  playerBody,
  reactivateReturns,
  releaseReturns,
} from "@/test/msw/squad";
import { watchLiveInsertion } from "@/test/liveRegion";
import { renderWithProviders } from "@/test/render";

// Load the lazy page's code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() => import("@/pages/squad/SquadPage"));

let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** Every non-GET request sent to the squad API, as "METHOD /path". */
const writes = () =>
  fetchSpy.mock.calls
    .map(([input, init]) => `${init?.method ?? "GET"} ${String(input)}`)
    .filter((request) => !request.startsWith("GET") && request.includes("/squad/"));

/**
 * The server's players: GET /squad/players answers from them as they are now, by the `status`
 * filter (active by default), counting the requests.
 */
function serverSquad(players: Player[]) {
  const store = { players, listGets: 0 };
  server.use(
    http.get("/squad/players", ({ request }) => {
      store.listGets += 1;
      const status = new URL(request.url).searchParams.get("status") ?? "active";
      return HttpResponse.json(
        store.players.filter(
          (player) => status === "all" || player.active === (status === "active"),
        ),
      );
    }),
  );
  return store;
}

async function renderSquad(
  players: Player[],
  { user = {}, url = "/app/squad" }: { user?: Partial<CurrentUser>; url?: string } = {},
) {
  server.use(refreshReturns("t1"), meReturns(user));
  const store = serverSquad(players);
  const rendered = renderWithProviders({ initialEntries: [url] });
  await screen.findByRole("link", { name: players[0].fullName });
  return { ...rendered, store };
}

const trigger = (name: string) =>
  screen.getByRole("button", { name: he.squad.actions.menu.replace("{{name}}", name) });

/** Opens `name`'s row menu and chooses `item`, by mouse: the dialog it opens. */
async function choose(name: string, item: string) {
  fireEvent.click(trigger(name));
  const menu = await screen.findByRole("menu");
  fireEvent.click(within(menu).getByRole("menuitem", { name: item }));
  return screen.findByRole("alertdialog");
}

describe("the row menu's in-place actions", () => {
  it("open their dialog once the menu has closed, focus inside it", async () => {
    await renderSquad([playerBody()]);

    const dialog = await choose("Yossi Levi", he.squad.actions.release);

    expect(screen.queryByRole("menu")).toBeNull();
    expect(dialog).toHaveAccessibleName("שחרור Yossi Levi");
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: he.squad.dialog.cancel })).toHaveFocus(),
    );
  });

  it("return focus to the row's trigger when the dialog closes", async () => {
    await renderSquad([playerBody()]);
    const dialog = await choose("Yossi Levi", he.squad.actions.release);

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.dialog.cancel }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    await waitFor(() => expect(trigger("Yossi Levi")).toHaveFocus());
    expect(writes()).toEqual([]);
  });

  it("work from the keyboard: menu → item → dialog → Escape → back on the trigger", async () => {
    await renderSquad([playerBody()]);
    const button = trigger("Yossi Levi");
    button.focus();

    fireEvent.keyDown(button, { key: "ArrowDown" });
    const menu = await screen.findByRole("menu");
    const release = within(menu).getByRole("menuitem", { name: he.squad.actions.release });
    await waitFor(() => expect(within(menu).getAllByRole("menuitem")[0]).toHaveFocus());
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    await waitFor(() => expect(release).toHaveFocus());
    fireEvent.keyDown(release, { key: "Enter" });

    const dialog = await screen.findByRole("alertdialog");
    await waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true));
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    await waitFor(() => expect(button).toHaveFocus());
    expect(writes()).toEqual([]);
  });

  it("never open the player from a click in the dialog (it's portaled, outside the row)", async () => {
    const { router } = await renderSquad([playerBody()]);
    const navigations: string[] = [];
    router.subscribe((state) => navigations.push(state.location.pathname));
    const dialog = await choose("Yossi Levi", he.squad.actions.release);

    fireEvent.click(dialog);
    fireEvent.click(within(dialog).getByText(he.squad.dialog.release.text));
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.dialog.cancel }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

    expect(navigations.filter((path) => path !== "/app/squad")).toEqual([]);
    expect(router.state.location.pathname).toBe("/app/squad");
  });
});

describe("releasing from a row", () => {
  it("sends exactly the row's version; the list refetches and the player leaves it", async () => {
    const players = [playerBody({ version: 7 }), playerBody({ id: "p2", fullName: "Avi Cohen" })];
    const { store } = await renderSquad(players);
    const release = releaseReturns(players[0], () => {
      store.players = [{ ...players[0], active: false, version: 8 }, players[1]];
      return HttpResponse.json(store.players[0]);
    });
    server.use(release.handler);
    const listGetsBefore = store.listGets;
    const dialog = await choose("Yossi Levi", he.squad.actions.release);

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.release }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(release.bodies).toEqual([{ version: 7 }]);
    await waitFor(() => expect(screen.queryByRole("link", { name: "Yossi Levi" })).toBeNull());
    expect(store.listGets).toBeGreaterThan(listGetsBefore);
    expect(screen.getByRole("link", { name: "Avi Cohen" })).toBeInTheDocument();
  });

  it("keeps the dialog when the list empties under it", async () => {
    const player = playerBody();
    const { store } = await renderSquad([player]);
    server.use(
      releaseReturns(player, () => {
        store.players = [{ ...player, active: false, version: 2 }];
        return conflict("STALE_VERSION");
      }).handler,
    );
    const dialog = await choose("Yossi Levi", he.squad.actions.release);

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.release }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(he.squad.dialog.stale);
    // The invalidation refetched the list: now empty, so the table is gone, but not the dialog.
    expect(await screen.findByText(he.squad.empty.active)).toBeInTheDocument();
    expect(screen.getByRole("alertdialog")).toBe(dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.dialog.reload }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
  });

  it("sends one request for a double click", async () => {
    const player = playerBody();
    await renderSquad([player]);
    const gate = deferred();
    const release = releaseReturns(player, async () => {
      await gate.promise;
      return HttpResponse.json({ ...player, active: false, version: 1 });
    });
    server.use(release.handler);
    const dialog = await choose("Yossi Levi", he.squad.actions.release);
    const confirm = within(dialog).getByRole("button", { name: he.squad.actions.release });

    fireEvent.click(confirm);
    fireEvent.click(confirm);
    await act(async () => gate.resolve());

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(release.bodies).toHaveLength(1);
  });

  it("says a 404 in the dialog and offers the reload (already on the squad)", async () => {
    const player = playerBody();
    await renderSquad([player]);
    server.use(
      releaseReturns(player, () => apiError(404, "Not Found", "Player not found")).handler,
    );
    const dialog = await choose("Yossi Levi", he.squad.actions.release);

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.release }));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(he.squad.player.notFound);
    expect(within(alert).getByRole("button", { name: he.squad.dialog.reload })).toBeInTheDocument();
  });
});

describe("re-activating from a row", () => {
  it("prefills the row's number and sends it with the row's version", async () => {
    const player = playerBody({ active: false, version: 4, jerseyNumber: 9 });
    await renderSquad([player], { url: "/app/squad?status=released" });
    const reactivate = reactivateReturns(player);
    server.use(reactivate.handler);
    const dialog = await choose("Yossi Levi", he.squad.actions.reactivate);

    expect(within(dialog).getByRole("textbox", { name: he.squad.fields.jerseyNumber })).toHaveValue(
      "9",
    );
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.reactivate }));

    await waitFor(() => expect(reactivate.bodies).toEqual([{ version: 4, jerseyNumber: 9 }]));
  });
});

describe("deleting from a row", () => {
  it("shows the notice above the table; the row goes after the refetch", async () => {
    const players = [
      playerBody({ fullName: "דני לוי" }),
      playerBody({ id: "p2", fullName: "Avi Cohen" }),
    ];
    const { store } = await renderSquad(players);
    const remove = deleteReturns("p1", () => {
      store.players = [players[1]];
      return new HttpResponse(null, { status: 204 });
    });
    server.use(remove.handler);
    const dialog = await choose("דני לוי", he.squad.actions.delete);

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.delete }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    const notice = screen.getByText("דני לוי נמחק לצמיתות.");
    expect(notice.closest("[role=status]")).not.toBeNull();
    await waitFor(() => expect(screen.queryByRole("link", { name: "דני לוי" })).toBeNull());
    expect(screen.getByRole("link", { name: "Avi Cohen" })).toBeInTheDocument();
    expect(remove.requests.count).toBe(1);
    // No GET of the deleted player: its card query was dropped, not refetched.
    expect(
      fetchSpy.mock.calls.filter(
        ([input, init]) =>
          String(input).endsWith("/squad/players/p1") && (init?.method ?? "GET") === "GET",
      ),
    ).toEqual([]);
  });

  it("announces the notice: its text goes into the status region that was already there", async () => {
    const player = playerBody({ fullName: "דני לוי" });
    const { store } = await renderSquad([player, playerBody({ id: "p2", fullName: "Avi" })]);
    server.use(
      deleteReturns("p1", () => {
        store.players = store.players.slice(1);
        return new HttpResponse(null, { status: 204 });
      }).handler,
    );
    const region = screen.getByRole("status");
    expect(region).toBeEmptyDOMElement();
    const dialog = await choose("דני לוי", he.squad.actions.delete);
    const watch = watchLiveInsertion("דני לוי נמחק לצמיתות.");

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.delete }));

    await waitFor(() => expect(region).toHaveTextContent("דני לוי נמחק לצמיתות."));
    watch.stop();
    expect(watch.result.intoExistingRegion).toBe(true);
    expect(screen.getByRole("status")).toBe(region);
  });

  it("treats a 404 as already gone: the same notice", async () => {
    const player = playerBody({ fullName: "דני לוי" });
    const { store } = await renderSquad([player, playerBody({ id: "p2", fullName: "Avi" })]);
    server.use(
      deleteReturns("p1", () => {
        store.players = store.players.slice(1);
        return apiError(404, "Not Found", "Player not found");
      }).handler,
    );
    const dialog = await choose("דני לוי", he.squad.actions.delete);

    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.actions.delete }));

    expect(await screen.findByText("דני לוי נמחק לצמיתות.")).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole("link", { name: "דני לוי" })).toBeNull());
  });

  it("isn't offered below ADMIN", async () => {
    await renderSquad([playerBody()], { user: { permissionLevel: "EDIT_FULL" } });

    fireEvent.click(trigger("Yossi Levi"));
    const menu = await screen.findByRole("menu");

    expect(within(menu).queryByRole("menuitem", { name: he.squad.actions.delete })).toBeNull();
  });
});
