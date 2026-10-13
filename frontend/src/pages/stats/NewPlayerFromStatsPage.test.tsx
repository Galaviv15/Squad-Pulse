import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse } from "msw";
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
import type { PermissionLevel } from "@/lib/auth/permissions";
import { PLAYER_STATS_QUERY_KEY, type PlayerStats } from "@/lib/matches/playerStats";
import { EMPTY_PLAYER_FORM, formValuesToCreateBody } from "@/lib/squad/form";
import { playerQueryKey } from "@/lib/squad/players";
import { deferred } from "@/test/deferred";
import { watchLiveInsertion } from "@/test/liveRegion";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import { refreshStatusBody, refreshStatusReturns } from "@/test/msw/matches";
import {
  createFromStatsReturns,
  emptyPlayerStats,
  playerRef,
  playerStatsBody,
  playerStatsReturns,
  squadPlayers,
  statsRow,
  statsRows,
} from "@/test/msw/playerStats";
import { server } from "@/test/msw/server";
import { conflict } from "@/test/msw/squad";
import { choose, combobox, errorOf, fields, input, submitButton, type } from "@/test/playerForm";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([
    import("@/pages/stats/NewPlayerFromStatsPage"),
    import("@/pages/stats/StatsPage"),
    import("@/pages/squad/PlayerCardPage"),
  ]),
);

const C = he.stats.createPlayer;
const ROW = statsRows.plain(); // "פרץ עומר", unlinked, no suggestion
const FORM_PATH = `/app/stats/${ROW.id}/new-player`;
const name = (template: string, value: string) => template.replace("{{name}}", value);

let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
});

afterEach(() => {
  vi.restoreAllMocks();
});

const requested = (method: string, pathname: string) =>
  fetchSpy.mock.calls.filter(([request, init]) => {
    const req = request instanceof Request ? request : null;
    const url = new URL(req?.url ?? String(request), "http://localhost");
    return url.pathname === pathname && (req?.method ?? init?.method ?? "GET") === method;
  }).length;

/**
 * Logged in as `level`, the stats screen (with `filter`) then the form for `rowId`, so Back has
 * somewhere to go. The stats answer from `store.stats` as it is then (counted in `reads`).
 */
async function renderForm({
  level = "EDIT_FULL",
  stats = playerStatsBody(),
  filter = "unlinked",
  rowId = ROW.id,
}: {
  level?: PermissionLevel;
  stats?: PlayerStats | (() => Response | Promise<Response>);
  filter?: string | null;
  rowId?: string;
} = {}) {
  const store = { stats };
  const read = playerStatsReturns(() =>
    typeof store.stats === "function" ? store.stats() : HttpResponse.json(store.stats),
  );
  server.use(
    refreshReturns("t1"),
    meReturns({ permissionLevel: level }),
    read.handler,
    refreshStatusReturns(refreshStatusBody()).handler,
  );
  const search = filter === null ? "" : `?filter=${filter}`;
  const rendered = renderWithProviders({
    initialEntries: [`/app/stats${search}`, `/app/stats/${rowId}/new-player${search}`],
  });
  await screen.findByRole("heading", { level: 1, name: C.title });
  return { ...rendered, store, reads: read.requests };
}

const settle = () => act(() => new Promise((resolve) => setTimeout(resolve, 30)));
const formReady = () => screen.findByRole("button", { name: he.squad.form.submitCreate });
const submit = () => fireEvent.click(submitButton(he.squad.form.submitCreate));

async function fillRequired() {
  await formReady();
  await choose(fields.primaryPosition, "CB");
  type(fields.dateOfBirth, "1998-05-20");
}

describe("the create-from-stats page's states", () => {
  it("shows the loading line, then the form", async () => {
    const gate = deferred();
    await renderForm({
      stats: async () => {
        await gate.promise;
        return HttpResponse.json(playerStatsBody());
      },
    });

    expect(screen.getByRole("status")).toHaveTextContent(he.matches.loading);
    expect(screen.queryByRole("textbox")).toBeNull();
    gate.resolve();
    expect(await formReady()).toBeInTheDocument();
  });

  it("shows a read error with a retry", async () => {
    let fail = true;
    const { reads } = await renderForm({
      stats: () =>
        fail
          ? apiError(500, "Internal Server Error", "An unexpected error occurred")
          : HttpResponse.json(playerStatsBody()),
    });

    expect(await screen.findByText(he.stats.loadError, {}, { timeout: 3000 })).toBeVisible();
    fail = false;
    const before = reads.count;
    fireEvent.click(screen.getByRole("button", { name: he.matches.retry }));

    expect(await formReady()).toBeInTheDocument();
    expect(reads.count).toBe(before + 1);
  });

  it.each([
    ["no such row", playerStatsBody(), "stats-row-gone"],
    ["NOT_MAPPED", emptyPlayerStats("NOT_MAPPED"), ROW.id],
    ["NO_DATA", emptyPlayerStats("NO_DATA"), ROW.id],
  ])("says the player is gone for %s, with the way back and no form", async (_n, stats, rowId) => {
    await renderForm({ stats, rowId });

    expect(await screen.findByText(C.rowGone)).toBeVisible();
    expect(screen.getByRole("link", { name: C.backToStats })).toHaveAttribute(
      "href",
      "/app/stats?filter=unlinked",
    );
    expect(screen.queryByRole("textbox")).toBeNull();
    expect(screen.queryByRole("button", { name: he.squad.form.submitCreate })).toBeNull();
  });

  it("says a linked row is linked, naming the player, with their card and the way back", async () => {
    await renderForm({ rowId: statsRows.linked().id });

    expect(await screen.findByText(name(C.alreadyLinked, "דניאל כהן"))).toBeVisible();
    expect(screen.getByRole("link", { name: he.squad.form.toCard })).toHaveAttribute(
      "href",
      "/app/squad/p1",
    );
    expect(screen.getByRole("link", { name: C.backToStats })).toHaveAttribute(
      "href",
      "/app/stats?filter=unlinked",
    );
    expect(screen.queryByRole("textbox")).toBeNull();
  });

  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as PermissionLevel[])(
    "shows %s the no-permission message, with no form and no stats request",
    async (level) => {
      server.use(refreshReturns("t1"), meReturns({ permissionLevel: level }));
      renderWithProviders({ initialEntries: [FORM_PATH] });

      expect(await screen.findByText(he.squad.noPermission)).toBeInTheDocument();
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(C.title);
      expect(screen.queryByRole("textbox")).toBeNull();
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(requested("GET", "/matches/player-stats")).toBe(0);
    },
  );
});

describe("the create-from-stats form", () => {
  it("is the normal create form, the scraped name pre-filled and editable, under the site's name and the hint", async () => {
    await renderForm();
    await formReady();

    expect(screen.getByText(name(C.fromSite, ROW.name))).toBeVisible();
    expect(screen.getByText(C.nameHint)).toBeVisible();
    expect(input(fields.fullName)).toHaveValue(ROW.name);
    type(fields.fullName, "עומר פרץ");
    expect(input(fields.fullName)).toHaveValue("עומר פרץ");

    for (const label of [fields.fullName, fields.dateOfBirth]) {
      expect(input(label)).toHaveAttribute("aria-required", "true");
    }
    for (const label of [fields.primaryPosition, fields.medicalStatus]) {
      expect(combobox(label)).toHaveAttribute("aria-required", "true");
    }
    for (const label of [fields.jerseyNumber, fields.heightCm, fields.weightKg]) {
      expect(input(label)).not.toHaveAttribute("aria-required");
    }
    expect(combobox(fields.secondaryPosition)).not.toHaveAttribute("aria-required");
    expect(combobox(fields.preferredFoot)).not.toHaveAttribute("aria-required");
    expect(combobox(fields.medicalStatus)).toHaveTextContent(he.squad.medical.FIT);
    // Exactly the normal form's fields: four text inputs + the date, four selects.
    expect(screen.getAllByRole("textbox")).toHaveLength(4);
    expect(screen.getAllByRole("combobox")).toHaveLength(4);
  });

  it("keeps the typed values when the stats are refetched in the background, even if the row changed", async () => {
    const { store, reads, queryClient } = await renderForm();
    await formReady();
    type(fields.fullName, "עומר פרץ");
    type(fields.jerseyNumber, "7");
    const before = reads.count;

    store.stats = playerStatsBody({
      players: [statsRow("d", "פרץ עומר", { linkedPlayer: playerRef(squadPlayers.omer) })],
    });
    await act(() => queryClient.refetchQueries({ queryKey: PLAYER_STATS_QUERY_KEY }));
    await settle(); // TanStack Query notifies its observers on a later tick

    expect(reads.count).toBe(before + 1);
    expect(screen.getByRole("button", { name: he.squad.form.submitCreate })).toBeInTheDocument();
    expect(input(fields.fullName)).toHaveValue("עומר פרץ");
    expect(input(fields.jerseyNumber)).toHaveValue("7");
  });
});

describe("saving", () => {
  it("sends one POST with what /app/squad/new would send, then goes back to the filtered stats, focusing and announcing", async () => {
    const created = createFromStatsReturns();
    server.use(created.handler);
    const { router, reads, queryClient } = await renderForm();
    await fillRequired();
    type(fields.fullName, "עומר פרץ");
    await choose(fields.secondaryPosition, "RB");
    type(fields.jerseyNumber, "10");
    type(fields.heightCm, "180");
    type(fields.weightKg, "75");
    await choose(fields.preferredFoot, he.squad.foot.LEFT);
    // Fresh forever unless invalidated: so the refetch below is the save's doing, not the stats
    // screen's mount (stale data refetches on mount anyway).
    queryClient.setQueryDefaults(PLAYER_STATS_QUERY_KEY, { staleTime: Infinity });
    const readsBefore = reads.count;
    const live = watchLiveInsertion(name(C.created, "עומר פרץ"));

    submit();

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/stats"));
    expect(created.requests).toEqual([
      {
        rowId: ROW.id,
        body: formValuesToCreateBody({
          ...EMPTY_PLAYER_FORM,
          fullName: "עומר פרץ",
          primaryPosition: "CB",
          secondaryPosition: "RB",
          jerseyNumber: "10",
          dateOfBirth: "1998-05-20",
          heightCm: "180",
          weightKg: "75",
          preferredFoot: "LEFT",
        }),
      },
    ]);
    expect(router.state.location.search).toBe("?filter=unlinked");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(await screen.findByRole("rowheader", { name: ROW.name })).toHaveFocus();
    expect(await screen.findByText(name(C.created, "עומר פרץ"))).toBeVisible();
    expect(live.result.intoExistingRegion).toBe(true);
    live.stop();
    // The stats are refetched; the squad caches updated as for any create.
    await waitFor(() => expect(reads.count).toBeGreaterThan(readsBefore));
    expect(queryClient.getQueryData(playerQueryKey("new1"))).toMatchObject({ id: "new1" });

    // Back skips the form: it was replaced.
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).toBe("/app/stats");
    expect(screen.queryByRole("button", { name: he.squad.form.submitCreate })).toBeNull();
  });

  it("goes back without a filter when it came without one", async () => {
    server.use(createFromStatsReturns().handler);
    const { router } = await renderForm({ filter: null });
    await fillRequired();

    submit();

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/stats"));
    expect(router.state.location.search).toBe("");
  });

  it("sends one request for a double submit", async () => {
    const gate = deferred();
    const created = createFromStatsReturns(undefined, gate.promise);
    server.use(created.handler);
    const { router } = await renderForm();
    await fillRequired();

    const button = submitButton(he.squad.form.submitCreate);
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.submit(button.closest("form")!);

    await waitFor(() => expect(button).toHaveTextContent(he.squad.form.saving));
    gate.resolve();
    await waitFor(() => expect(router.state.location.pathname).toBe("/app/stats"));
    expect(created.requests).toHaveLength(1);
  });

  it("never goes through POST /squad/players or PUT …/link", async () => {
    server.use(createFromStatsReturns().handler);
    const { router } = await renderForm();
    await fillRequired();

    submit();

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/stats"));
    expect(requested("POST", "/squad/players")).toBe(0);
    expect(requested("PUT", `/matches/player-stats/${ROW.id}/link`)).toBe(0);
    expect(requested("POST", `/matches/player-stats/${ROW.id}/player`)).toBe(1);
  });
});

describe("ביטול", () => {
  it("goes back to the filtered stats and focuses the row, with no request and no announcement", async () => {
    const { router } = await renderForm();
    await formReady();
    const cancel = screen.getByRole("link", { name: he.squad.form.cancel });
    expect(cancel).toHaveAttribute("href", "/app/stats?filter=unlinked");

    fireEvent.click(cancel);

    expect(await screen.findByRole("rowheader", { name: ROW.name })).toHaveFocus();
    expect(router.state.location.search).toBe("?filter=unlinked");
    expect(requested("POST", `/matches/player-stats/${ROW.id}/player`)).toBe(0);
    expect(screen.queryByText(name(C.created, ROW.name))).toBeNull();
    // The state was read once and removed from the entry.
    await waitFor(() => expect(router.state.location.state).toBeNull());
  });
});

describe("the save's errors", () => {
  it("puts a 400's field errors on their fields", async () => {
    server.use(
      createFromStatsReturns(() =>
        apiError(400, "Validation Failed", "Request validation failed", [
          "dateOfBirth: age must be between 18 and 99",
        ]),
      ).handler,
    );
    await renderForm();
    await fillRequired();

    submit();

    await waitFor(() =>
      expect(errorOf(input(fields.dateOfBirth))).toBe(he.squad.form.errors.invalidValue),
    );
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("puts a taken jersey number on its field, without refetching the stats", async () => {
    server.use(createFromStatsReturns(() => conflict("JERSEY_NUMBER_TAKEN")).handler);
    const { reads } = await renderForm();
    await fillRequired();
    type(fields.jerseyNumber, "7");
    const before = reads.count;

    submit();

    await waitFor(() =>
      expect(errorOf(input(fields.jerseyNumber))).toBe(he.squad.form.errors.jerseyNumberTaken),
    );
    expect(input(fields.jerseyNumber)).toHaveFocus();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(reads.count).toBe(before);
  });

  it.each([
    ["a 403", () => apiError(403, "Forbidden", "Access denied"), he.squad.noPermission],
    [
      "a 500",
      () => apiError(500, "Internal Server Error", "An unexpected error occurred"),
      he.squad.form.saveFailed,
    ],
    ["no response", () => HttpResponse.error(), he.squad.form.saveFailed],
    ["an unknown 409 code", () => conflict("SOMETHING_NEW"), he.squad.form.saveInvalid],
  ])("shows %s as on /app/squad/new, keeping the input and the stats", async (_n, answer, text) => {
    server.use(createFromStatsReturns(answer).handler);
    const { reads } = await renderForm();
    await fillRequired();
    const before = reads.count;

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(input(fields.fullName)).toHaveValue(ROW.name);
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(reads.count).toBe(before);
  });

  it.each([
    ["a 404", () => apiError(404, "Not Found", "Not found"), C.errors.notFound, true],
    [
      "a 409 STATS_ROW_ALREADY_LINKED",
      () => conflict("STATS_ROW_ALREADY_LINKED"),
      C.errors.alreadyLinked,
      true,
    ],
    [
      "a 409 CONCURRENT_MODIFICATION",
      () => conflict("CONCURRENT_MODIFICATION"),
      C.errors.concurrent,
      false,
    ],
  ])(
    "shows %s in plain Hebrew, keeps the form and refetches the stats",
    async (_n, answer, text, back) => {
      server.use(createFromStatsReturns(answer).handler);
      const { reads } = await renderForm();
      await fillRequired();
      type(fields.jerseyNumber, "7");
      const before = reads.count;

      submit();

      const alert = await screen.findByRole("alert");
      expect(alert).toHaveTextContent(text);
      expect(screen.queryByText("Conflict")).toBeNull();
      if (back) {
        expect(within(alert).getByRole("link", { name: C.backToStats })).toHaveAttribute(
          "href",
          "/app/stats?filter=unlinked",
        );
      } else {
        expect(within(alert).queryByRole("link")).toBeNull();
      }
      expect(input(fields.jerseyNumber)).toHaveValue("7");
      expect(submitButton(he.squad.form.submitCreate)).toBeEnabled();
      await waitFor(() => expect(reads.count).toBe(before + 1));
    },
  );
});

describe("the return path", () => {
  it.each(["https://evil.example", "//evil.example", "UNLINKED", "unlinked&filter=unlinked"])(
    "takes only the known filter value, never %s",
    async (forged) => {
      await renderForm({ filter: encodeURIComponent(forged) });
      await formReady();

      expect(screen.getByRole("link", { name: he.squad.form.cancel })).toHaveAttribute(
        "href",
        "/app/stats",
      );
    },
  );

  it("ignores a URL in history state", async () => {
    server.use(createFromStatsReturns().handler);
    const { router } = await renderForm();
    await formReady();
    await act(() =>
      router.navigate(`${FORM_PATH}?filter=unlinked`, {
        replace: true,
        state: { returnTo: "https://evil.example", statsRowId: "//evil.example" },
      }),
    );
    await fillRequired();

    submit();

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/stats"));
    expect(router.state.location.search).toBe("?filter=unlinked");
  });
});
