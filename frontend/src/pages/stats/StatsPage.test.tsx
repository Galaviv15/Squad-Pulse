import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse } from "msw";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import he from "@/i18n/locales/he.json";
import { hasPermission, type PermissionLevel } from "@/lib/auth/permissions";
import type { PlayerStats } from "@/lib/matches/playerStats";
import type { RefreshStatus } from "@/lib/matches/types";
import { deferred } from "@/test/deferred";
import { watchLiveInsertion } from "@/test/liveRegion";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import {
  dashboardMatchesReturn,
  fixturesReturns,
  leagueTableReturns,
  refreshStatusBody,
  refreshStatusReturns,
} from "@/test/msw/matches";
import {
  allSquadPlayers,
  emptyPlayerStats,
  linkReturns,
  playerRef,
  playerStatsBody,
  playerStatsReturns,
  squadPlayers,
  statsRow,
  statsRows,
  unlinkRowReturns,
} from "@/test/msw/playerStats";
import { server } from "@/test/msw/server";
import { conflict, recordedPlayers, summaryReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([import("@/pages/stats/StatsPage"), import("@/pages/matches/MatchesPage")]),
);

const S = he.stats;
const failure = () => apiError(500, "Internal Server Error", "An unexpected error occurred");
const name = (template: string, value: string) => template.replace("{{name}}", value);

/**
 * Logged in as `level` at `path`, the stats answering from `store.stats` as it is now (counted in
 * `reads`). From EDIT_FULL up the refresh status answers `statuses` (the control requests it);
 * below that there is no handler, so a request would fail the test. Waits for the <h1>.
 */
async function renderStats({
  level = "ADMIN",
  stats = playerStatsBody(),
  path = "/app/stats",
  statuses = [refreshStatusBody()],
}: {
  level?: PermissionLevel;
  stats?: PlayerStats | (() => Response);
  path?: string;
  statuses?: RefreshStatus[];
} = {}) {
  const store = { stats };
  const read = playerStatsReturns(() =>
    typeof store.stats === "function" ? store.stats() : HttpResponse.json(store.stats),
  );
  server.use(refreshReturns("t1"), meReturns({ permissionLevel: level }), read.handler);
  if (hasPermission(level, "EDIT_FULL")) {
    server.use(refreshStatusReturns(...statuses).handler);
  }
  const rendered = renderWithProviders({ initialEntries: [path] });
  await screen.findByRole("heading", { level: 1, name: he.nav.stats });
  return { ...rendered, store, reads: read.requests };
}

const table = () => screen.findByRole("table", { name: S.tableLabel });
const rowOf = (rowName: string) =>
  screen.getByRole("rowheader", { name: rowName }).closest("tr") as HTMLElement;
const rowNames = () => screen.getAllByRole("rowheader").map((cell) => cell.textContent);
const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 30)));

describe("the sidebar's stats item", () => {
  it("is a live link after משחקים, opening /app/stats with its title", async () => {
    server.use(
      refreshReturns("t1"),
      meReturns({ permissionLevel: "VIEW_ONLY" }),
      summaryReturns(),
      ...dashboardMatchesReturn(),
      playerStatsReturns().handler,
    );
    const { router } = renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
    const nav = screen.getByRole("navigation", { name: he.shell.navLabel });
    const labels = within(nav)
      .getAllByRole("link")
      .map((link) => link.textContent);
    expect(labels.indexOf(he.nav.stats)).toBe(labels.indexOf(he.nav.matches) + 1);

    fireEvent.click(within(nav).getByRole("link", { name: he.nav.stats }));

    expect(await screen.findByRole("heading", { level: 1, name: he.nav.stats })).toBeVisible();
    expect(router.state.location.pathname).toBe("/app/stats");
    expect(within(nav).getByRole("link", { name: he.nav.stats })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(within(nav).getByRole("link", { name: he.nav.dashboard })).not.toHaveAttribute(
      "aria-current",
    );
    expect(document.title).toBe(`${he.nav.stats} · SquadPulse`);
  });

  it("leaves the matches screen with its two tabs", async () => {
    server.use(
      refreshReturns("t1"),
      meReturns({ permissionLevel: "VIEW_ONLY" }),
      fixturesReturns(),
      leagueTableReturns(),
    );
    renderWithProviders({ initialEntries: ["/app/matches"] });

    const tabs = await screen.findByRole("radiogroup", { name: he.matches.tabsLabel });
    expect(within(tabs).getAllByRole("radio")).toHaveLength(2);
  });
});

describe("the stats table", () => {
  it("has every column, the card columns named in full for screen readers", async () => {
    await renderStats({ level: "VIEW_ONLY" });

    const headers = within(await table()).getAllByRole("columnheader");
    const expected = [
      S.columns.name,
      S.columns.squadPlayer,
      S.columns.games,
      S.columns.starts,
      S.columns.cameOnAsSub,
      S.columns.subbedOff,
      S.columns.minutes,
      S.columns.goals,
      "כרטיסים צהובים, ליגה + גביע",
      "כרטיסים צהובים, טוטו",
      "כרטיסים אדומים",
    ];
    expect(headers).toHaveLength(expected.length);
    headers.forEach((header, i) => {
      expect(header).toHaveAccessibleName(expected[i]);
      expect(header).toHaveAttribute("scope", "col");
    });
    // The short visible texts are shown beside the decorative card marks.
    expect(within(headers[9]).getByText(S.columns.yellowToto)).toBeVisible();
  });

  it("shows every row in the server's order, the site's name as the row header", async () => {
    await renderStats({ level: "VIEW_ONLY" });
    await table();

    expect(rowNames()).toEqual([
      "כהן דניאל",
      "לוי יוסי",
      "מזרחי אבי",
      "פרץ עומר",
      "ביטון רון",
      "חדד משה",
    ]);
    expect(screen.getAllByRole("rowheader")[0]).toHaveAttribute("scope", "row");
  });

  it("shows a 0-minute row, and minutes over 90 × games without any warning", async () => {
    await renderStats({ level: "VIEW_ONLY" });
    await table();

    const zero = within(rowOf("ביטון רון")).getAllByRole("cell");
    expect(zero.slice(1).map((cell) => cell.textContent)).toEqual([
      "0",
      "0",
      "0",
      "0",
      "0",
      "0",
      "0",
      "0",
      "0",
    ]);
    const addedTime = rowOf("חדד משה");
    expect(within(addedTime).getByText("197")).toBeInTheDocument();
    expect(within(addedTime).queryByRole("alert")).toBeNull();
    expect(within(addedTime).queryByRole("img")).toBeNull();
  });

  it("never shows a row's opaque id", async () => {
    await renderStats();
    await table();

    expect(document.body.textContent).not.toContain("stats-row");
  });

  it("shows the data's last-updated time in Israel time", async () => {
    await renderStats({ level: "VIEW_ONLY" });

    expect(await screen.findByText("עודכן לאחרונה: 12.10.2026 18:30")).toBeInTheDocument();
    expect(screen.getByText(S.description)).toBeInTheDocument();
  });
});

describe("the stats screen's states", () => {
  it("NOT_MAPPED: the not-mapped text, no filter, counts or refresh control", async () => {
    await renderStats({
      stats: emptyPlayerStats("NOT_MAPPED"),
      statuses: [refreshStatusBody({ state: "NOT_MAPPED" })],
    });

    expect(await screen.findByText(he.matches.notMapped)).toBeInTheDocument();
    await settle();
    expect(screen.queryByRole("radiogroup", { name: S.filter.label })).toBeNull();
    expect(screen.queryByText(/מקושרים/)).toBeNull();
    expect(screen.queryByRole("button", { name: he.matches.refresh.button })).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByText(/^עודכן לאחרונה/)).toBeNull();
  });

  it("NO_DATA: its own text, and an EDIT_FULL user still has the refresh control", async () => {
    await renderStats({ level: "EDIT_FULL", stats: emptyPlayerStats("NO_DATA") });

    expect(await screen.findByText(S.noData)).toBeInTheDocument();
    expect(
      await screen.findByRole("button", { name: he.matches.refresh.button }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("radiogroup", { name: S.filter.label })).toBeNull();
  });

  it("shows a read error with a retry that loads the table", async () => {
    const { store } = await renderStats({ level: "VIEW_ONLY", stats: failure });

    expect(await screen.findByText(S.loadError)).toBeInTheDocument();
    store.stats = playerStatsBody();
    fireEvent.click(screen.getByRole("button", { name: he.matches.retry }));

    expect(await table()).toBeInTheDocument();
  });

  it("shows the loading line until the answer arrives", async () => {
    const gate = deferred<void>();
    server.use(
      refreshReturns("t1"),
      meReturns({ permissionLevel: "VIEW_ONLY" }),
      playerStatsReturns(playerStatsBody(), { gate: gate.promise }).handler,
    );
    renderWithProviders({ initialEntries: ["/app/stats"] });

    expect(await screen.findByText(he.matches.loading)).toBeInTheDocument();
    gate.resolve();
    expect(await table()).toBeInTheDocument();
  });
});

describe("who sees what", () => {
  it.each(["VIEW_ONLY", "EDIT_PARTIAL"] as const)(
    "%s: linked names link to the card; no suggestion, control, filter or counts, and neither GET /matches/refresh nor GET /squad/players",
    async (level) => {
      // No handler for either: a request would fail the test (the unmocked-request guard).
      await renderStats({ level });
      await table();
      await settle();

      expect(within(rowOf("כהן דניאל")).getByRole("link", { name: "דניאל כהן" })).toHaveAttribute(
        "href",
        "/app/squad/p1",
      );
      const released = rowOf("לוי יוסי");
      expect(within(released).getByRole("link", { name: "יוסי לוי" })).toHaveAttribute(
        "href",
        "/app/squad/p2",
      );
      expect(within(released).getByText(he.squad.released)).toBeInTheDocument();
      expect(within(rowOf("פרץ עומר")).getByText(S.notLinked)).toBeInTheDocument();
      expect(within(rowOf("מזרחי אבי")).getByText(S.notLinked)).toBeInTheDocument();
      expect(screen.queryByText(/^הצעה:/)).toBeNull();
      expect(within(await table()).queryAllByRole("button")).toEqual([]);
      expect(screen.queryByRole("button", { name: /רענון/ })).toBeNull();
      expect(screen.queryByRole("radiogroup", { name: S.filter.label })).toBeNull();
      expect(screen.queryByText(/מקושרים/)).toBeNull();
    },
  );

  it.each(["EDIT_FULL", "ADMIN"] as const)("%s gets every control", async (level) => {
    await renderStats({ level });
    await table();

    expect(
      await screen.findByRole("button", { name: he.matches.refresh.button }),
    ).toBeInTheDocument();
    expect(screen.getByRole("radiogroup", { name: S.filter.label })).toBeInTheDocument();
    expect(screen.getByText(name(S.suggestion, "אבי מזרחי"))).toBeInTheDocument();
    const suggested = rowOf("מזרחי אבי");
    expect(
      within(suggested).getByRole("button", { name: name(S.confirmLabel, "מזרחי אבי") }),
    ).toBeInTheDocument();
    expect(
      within(suggested).getByRole("button", { name: name(S.otherLabel, "מזרחי אבי") }),
    ).toBeInTheDocument();
    expect(
      within(rowOf("פרץ עומר")).getByRole("button", { name: name(S.linkToLabel, "פרץ עומר") }),
    ).toBeInTheDocument();
    expect(
      within(rowOf("כהן דניאל")).getByRole("button", { name: name(S.menu, "כהן דניאל") }),
    ).toBeInTheDocument();
  });

  it("never links a suggestion by itself", async () => {
    const put = linkReturns();
    server.use(put.handler);
    await renderStats();
    await table();
    await settle();

    expect(put.requests).toEqual([]);
  });
});

/** The stats after `rowId` is linked to `player`: the suggestion row linked, nothing else. */
const linkedOnServer = (rowName: string, player: typeof squadPlayers.avi) =>
  playerStatsBody({
    players: playerStatsBody().players.map((row) =>
      row.name === rowName
        ? { ...row, linkedPlayer: playerRef(player), suggestedPlayer: null }
        : row,
    ),
  });

describe("confirming a suggestion", () => {
  it("sends one PUT with the suggested player (also for a double click), then refetches the list", async () => {
    const { store, reads } = await renderStats({ level: "EDIT_FULL" });
    await table();
    const put = linkReturns((rowId) => {
      store.stats = linkedOnServer("מזרחי אבי", squadPlayers.avi);
      // A row that isn't what the screen shows: it must refetch, never patch from this.
      return HttpResponse.json(statsRow("c", "מזרחי אבי", { id: rowId }));
    });
    server.use(put.handler);
    const confirm = within(rowOf("מזרחי אבי")).getByRole("button", {
      name: name(S.confirmLabel, "מזרחי אבי"),
    });

    fireEvent.click(confirm);
    fireEvent.click(confirm);

    expect(
      await within(rowOf("מזרחי אבי")).findByRole("link", { name: "אבי מזרחי" }),
    ).toBeInTheDocument();
    expect(put.requests).toEqual([{ rowId: "stats-row-c", body: { playerId: "p3" } }]);
    expect(reads.count).toBe(2);
    expect(screen.queryByText(/^הצעה:/)).toBeNull();
  });

  it("puts focus on the row's header once the list shows the link (the button is gone)", async () => {
    const { store } = await renderStats({ level: "EDIT_FULL" });
    await table();
    server.use(
      linkReturns(() => {
        store.stats = linkedOnServer("מזרחי אבי", squadPlayers.avi);
        return HttpResponse.json(statsRow("c", "x"));
      }).handler,
    );
    const confirm = within(rowOf("מזרחי אבי")).getByRole("button", {
      name: name(S.confirmLabel, "מזרחי אבי"),
    });
    confirm.focus();

    fireEvent.click(confirm);

    await within(rowOf("מזרחי אבי")).findByRole("link", { name: "אבי מזרחי" });
    expect(confirm.isConnected).toBe(false);
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(screen.getByRole("rowheader", { name: "מזרחי אבי" }));
  });

  it("leaves focus on the button when the PUT fails", async () => {
    await renderStats({ level: "EDIT_FULL" });
    await table();
    server.use(linkReturns(failure).handler);
    const confirm = within(rowOf("מזרחי אבי")).getByRole("button", {
      name: name(S.confirmLabel, "מזרחי אבי"),
    });
    confirm.focus();

    fireEvent.click(confirm);

    await within(rowOf("מזרחי אבי")).findByRole("alert");
    await waitFor(() => expect(confirm).toBeEnabled());
    expect(document.activeElement).toBe(confirm);
  });

  it("disables the row's buttons while the PUT runs", async () => {
    await renderStats({ level: "EDIT_FULL" });
    await table();
    const gate = deferred<void>();
    server.use(linkReturns(undefined, gate.promise).handler);
    const row = rowOf("מזרחי אבי");

    fireEvent.click(within(row).getByRole("button", { name: name(S.confirmLabel, "מזרחי אבי") }));

    await waitFor(() =>
      expect(
        within(row).getByRole("button", { name: name(S.otherLabel, "מזרחי אבי") }),
      ).toBeDisabled(),
    );
    expect(
      within(row).getByRole("button", { name: name(S.confirmLabel, "מזרחי אבי") }),
    ).toBeDisabled();
    gate.resolve();
    await settle();
  });

  it.each([
    ["404", () => apiError(404, "Not Found", "Player statistics not found"), S.errors.notFound, 2],
    ["403", () => apiError(403, "Forbidden", "Access denied"), he.squad.noPermission, 1],
    ["500", failure, he.squad.dialog.failed, 1],
    ["a network error", () => HttpResponse.error(), he.squad.dialog.failed, 1],
  ] as const)(
    "shows a %s in the row, refetching only when the list is out of date",
    async (_, answer, text, readsAfter) => {
      const { reads } = await renderStats({ level: "EDIT_FULL" });
      await table();
      server.use(linkReturns(answer).handler);
      const row = rowOf("מזרחי אבי");

      fireEvent.click(within(row).getByRole("button", { name: name(S.confirmLabel, "מזרחי אבי") }));

      expect(await within(row).findByRole("alert")).toHaveTextContent(text);
      await settle();
      expect(reads.count).toBe(readsAfter);
    },
  );
});

describe("the picker", () => {
  async function openPicker(button: string, rowName: string) {
    fireEvent.click(within(rowOf(rowName)).getByRole("button", { name: name(button, rowName) }));
    return screen.findByRole("alertdialog", { name: name(S.picker.title, rowName) });
  }

  it('isn\'t requested before it opens; from "אחר…" it lists the whole squad', async () => {
    const squad = recordedPlayers(allSquadPlayers());
    server.use(squad.handler);
    await renderStats({ level: "EDIT_FULL" });
    await table();
    await settle();
    expect(squad.queries).toEqual([]);

    const dialog = await openPicker(S.otherLabel, "מזרחי אבי");

    expect(dialog).toHaveAccessibleDescription(S.picker.text);
    expect(await within(dialog).findAllByRole("radio")).toHaveLength(6);
    expect(squad.queries).toEqual(["status=all"]);
  });

  it("searches, disables a player linked to another row, offers released players, and saves one PUT", async () => {
    server.use(recordedPlayers(allSquadPlayers()).handler);
    const { store, reads } = await renderStats({ level: "EDIT_FULL" });
    await table();
    const put = linkReturns(() => {
      store.stats = linkedOnServer("מזרחי אבי", squadPlayers.moshe);
      return HttpResponse.json(statsRow("c", "x"));
    });
    server.use(put.handler);
    const dialog = await openPicker(S.otherLabel, "מזרחי אבי");
    await within(dialog).findAllByRole("radio");

    const daniel = within(dialog).getByRole("radio", { name: /דניאל כהן/ });
    expect(daniel).toBeDisabled();
    expect(daniel).toHaveAccessibleDescription(name(S.picker.linkedTo, "כהן דניאל"));
    const ron = within(dialog).getByRole("radio", { name: /רון ביטון/ });
    expect(ron).toBeEnabled();
    expect(ron.closest("label")).toHaveTextContent(he.squad.released);
    const save = within(dialog).getByRole("button", { name: S.picker.confirm });
    expect(save).toBeDisabled();

    fireEvent.change(within(dialog).getByLabelText(S.picker.search), {
      target: { value: " משה  חד " },
    });
    expect(within(dialog).getAllByRole("radio")).toHaveLength(1);
    fireEvent.click(within(dialog).getByRole("radio", { name: /משה חדד/ }));
    expect(save).toBeEnabled();
    fireEvent.click(save);
    fireEvent.click(save);

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(put.requests).toEqual([{ rowId: "stats-row-c", body: { playerId: "p6" } }]);
    expect(
      await within(rowOf("מזרחי אבי")).findByRole("link", { name: "משה חדד" }),
    ).toBeInTheDocument();
    expect(reads.count).toBe(2);
  });

  it('opens from "קישור לשחקן…" on a row without a suggestion', async () => {
    server.use(recordedPlayers(allSquadPlayers()).handler);
    await renderStats({ level: "EDIT_FULL" });
    await table();

    const dialog = await openPicker(S.linkToLabel, "פרץ עומר");

    expect(await within(dialog).findAllByRole("radio")).toHaveLength(6);
    expect(within(dialog).queryByRole("radio", { checked: true })).toBeNull();
  });

  it("says when the squad is empty", async () => {
    server.use(recordedPlayers([]).handler);
    await renderStats({ level: "EDIT_FULL" });
    await table();

    const dialog = await openPicker(S.linkToLabel, "פרץ עומר");

    expect(await within(dialog).findByText(S.picker.empty)).toBeInTheDocument();
  });

  it("shows a 409 PLAYER_ALREADY_LINKED it couldn't foresee (linked to a row not in the snapshot), and refetches", async () => {
    server.use(recordedPlayers(allSquadPlayers()).handler);
    const { reads } = await renderStats({ level: "EDIT_FULL" });
    await table();
    const put = linkReturns(() => conflict("PLAYER_ALREADY_LINKED"));
    server.use(put.handler);
    const dialog = await openPicker(S.linkToLabel, "פרץ עומר");
    await within(dialog).findAllByRole("radio");

    // Omer is linked to nothing the screen shows, so he's selectable.
    const omer = within(dialog).getByRole("radio", { name: /עומר פרץ/ });
    expect(omer).toBeEnabled();
    fireEvent.click(omer);
    fireEvent.click(within(dialog).getByRole("button", { name: S.picker.confirm }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(S.errors.alreadyLinked);
    await waitFor(() => expect(reads.count).toBe(2));
    expect(put.requests).toHaveLength(1);
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });
});

describe("a linked row's menu", () => {
  async function choose(rowName: string, item: string) {
    fireEvent.click(within(rowOf(rowName)).getByRole("button", { name: name(S.menu, rowName) }));
    const menu = await screen.findByRole("menu");
    fireEvent.click(within(menu).getByRole("menuitem", { name: item }));
  }

  it('"שינוי קישור" opens the picker with the current player selected, and saves the new one', async () => {
    server.use(recordedPlayers(allSquadPlayers()).handler);
    await renderStats({ level: "EDIT_FULL" });
    await table();
    const put = linkReturns();
    server.use(put.handler);

    await choose("כהן דניאל", S.change);
    const dialog = await screen.findByRole("alertdialog", {
      name: name(S.picker.title, "כהן דניאל"),
    });
    await within(dialog).findAllByRole("radio");

    expect(within(dialog).getByRole("radio", { name: /דניאל כהן/ })).toBeChecked();
    fireEvent.click(within(dialog).getByRole("radio", { name: /עומר פרץ/ }));
    fireEvent.click(within(dialog).getByRole("button", { name: S.picker.confirm }));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(put.requests).toEqual([{ rowId: "stats-row-a", body: { playerId: "p4" } }]);
  });

  it('"ביטול קישור" sends one DELETE at once, announces it in a status region already there, and refetches', async () => {
    const { store, reads } = await renderStats({ level: "EDIT_FULL" });
    await table();
    const remove = unlinkRowReturns(() => {
      store.stats = playerStatsBody({
        players: playerStatsBody().players.map((row) =>
          row.name === "כהן דניאל" ? { ...row, linkedPlayer: null } : row,
        ),
      });
      return new HttpResponse(null, { status: 204 });
    });
    server.use(remove.handler);
    const watch = watchLiveInsertion(S.unlinked);

    await choose("כהן דניאל", S.unlink);

    await waitFor(() => expect(watch.result.intoExistingRegion).not.toBeNull());
    watch.stop();
    expect(watch.result.intoExistingRegion).toBe(true);
    expect(screen.queryByRole("alertdialog")).toBeNull();
    expect(remove.requests).toEqual(["stats-row-a"]);
    expect(
      await within(rowOf("כהן דניאל")).findByRole("button", {
        name: name(S.linkToLabel, "כהן דניאל"),
      }),
    ).toBeInTheDocument();
    expect(reads.count).toBe(2);
    expect(document.activeElement).not.toBe(document.body);
    expect(document.activeElement).toBe(screen.getByRole("rowheader", { name: "כהן דניאל" }));
  });
});

describe("a refresh run's end", () => {
  const NOW = Date.parse("2026-10-12T15:00:00Z");
  const at = (minutes: number) => new Date(NOW + minutes * 60_000).toISOString();

  beforeEach(() => {
    vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"], now: NOW });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it.each(["SUCCEEDED", "FAILED"] as const)(
    "RUNNING → %s refetches the player stats",
    async (state) => {
      const { reads } = await renderStats({
        level: "EDIT_FULL",
        statuses: [
          refreshStatusBody({ state: "RUNNING", startedAt: at(0), nextRefreshAt: at(10) }),
          refreshStatusBody({
            state,
            failureReason: state === "FAILED" ? "PARSE_ERROR" : null,
            startedAt: at(0),
            finishedAt: at(0.1),
            nextRefreshAt: at(10),
          }),
        ],
      });
      await table();
      expect(await screen.findByText(he.matches.refresh.scanning)).toBeInTheDocument();
      expect(reads.count).toBe(1);

      await act(() => vi.advanceTimersByTimeAsync(3_000));
      // MSW answers on real timers: give the answers and TanStack's notifications a moment.
      await settle();

      await waitFor(() => expect(reads.count).toBe(2));
    },
  );
});

describe("the filter", () => {
  const filterRadio = (label: string) => screen.getByRole("radio", { name: label });

  it("shows every row by default, with the counts", async () => {
    await renderStats();
    await table();

    expect(filterRadio("כל השחקנים (6)")).toBeChecked();
    expect(screen.getByRole("radio", { name: "ממתינים לקישור (4)" })).not.toBeChecked();
    expect(rowNames()).toHaveLength(6);
    expect(screen.getByText("2 מקושרים · 1 עם הצעה · 3 ללא קישור")).toBeInTheDocument();
  });

  it("?filter=unlinked shows only the rows without a link, suggested ones included", async () => {
    await renderStats({ path: "/app/stats?filter=unlinked" });
    await table();

    expect(filterRadio("ממתינים לקישור (4)")).toBeChecked();
    expect(rowNames()).toEqual(["מזרחי אבי", "פרץ עומר", "ביטון רון", "חדד משה"]);
  });

  it("keeps the choice in the URL, replacing the entry", async () => {
    const { router } = await renderStats();
    await table();

    fireEvent.click(filterRadio("ממתינים לקישור (4)"));

    await waitFor(() => expect(router.state.location.search).toBe("?filter=unlinked"));
    expect(router.state.historyAction).toBe("REPLACE");
    expect(rowNames()).toHaveLength(4);

    fireEvent.click(filterRadio("כל השחקנים (6)"));

    await waitFor(() => expect(router.state.location.search).toBe(""));
    expect(rowNames()).toHaveLength(6);
  });

  it("falls back to all for an unknown value", async () => {
    await renderStats({ path: "/app/stats?filter=linked" });
    await table();

    expect(filterRadio("כל השחקנים (6)")).toBeChecked();
    expect(rowNames()).toHaveLength(6);
  });

  it("says when every player is linked, and counts one linked player in the singular", async () => {
    await renderStats({
      path: "/app/stats?filter=unlinked",
      stats: playerStatsBody({ players: [statsRows.linked()] }),
    });

    expect(await screen.findByText(S.filter.allLinked)).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.getByText("1 מקושר · 0 עם הצעה · 0 ללא קישור")).toBeInTheDocument();
  });
});
