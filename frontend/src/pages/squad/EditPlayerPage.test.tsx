import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, PermissionLevel } from "@/lib/auth/currentUser";
import { playerQueryKey, SQUAD_PLAYERS_QUERY_KEY } from "@/lib/squad/players";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { conflict, playerBody, playerReturns, updatePlayerReturns } from "@/test/msw/squad";
import { combobox, errorOf, fields, input, submitButton, type } from "@/test/playerForm";
import { renderWithProviders } from "@/test/render";

let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
});

afterEach(() => {
  vi.restoreAllMocks();
});

const fetchedPaths = () =>
  fetchSpy.mock.calls.map(([input]) => new URL(String(input), "http://localhost").pathname);

const submit = () => fireEvent.click(submitButton(he.squad.form.submitEdit));

/**
 * Logged in at p1's edit form, the server's copy of p1 read from `server.player` on every GET
 * (so a test can change it), after p1's card in the history.
 */
async function renderEdit(player: Player = playerBody(), user: Partial<CurrentUser> = {}) {
  const state = { player };
  server.use(
    refreshReturns("t1"),
    meReturns(user),
    playerReturns(() => state.player),
  );
  const rendered = renderWithProviders({
    initialEntries: ["/app/squad/p1", "/app/squad/p1/edit"],
  });
  await screen.findByRole("heading", { level: 1, name: he.squad.editPlayer });
  return { ...rendered, server: state };
}

const ready = () => screen.findByRole("button", { name: he.squad.form.submitEdit });

describe("the edit form", () => {
  it("is filled from the player, a null as an empty field", async () => {
    await renderEdit(
      playerBody({
        secondaryPosition: null,
        jerseyNumber: null,
        heightCm: null,
        weightKg: 80,
        preferredFoot: null,
        medicalStatus: "INJURED",
      }),
    );
    await ready();

    expect(input(fields.fullName)).toHaveValue("Yossi Levi");
    expect(combobox(fields.primaryPosition)).toHaveTextContent("CB");
    expect(combobox(fields.secondaryPosition)).toHaveTextContent(he.squad.form.none);
    expect(input(fields.jerseyNumber)).toHaveValue("");
    expect(input(fields.dateOfBirth)).toHaveValue("1998-05-20");
    expect(input(fields.heightCm)).toHaveValue("");
    expect(input(fields.weightKg)).toHaveValue("80");
    expect(combobox(fields.preferredFoot)).toHaveTextContent(he.squad.form.none);
    expect(combobox(fields.medicalStatus)).toHaveTextContent(he.squad.medical.INJURED);
    expect(screen.getByRole("link", { name: he.squad.form.cancel })).toHaveAttribute(
      "href",
      "/app/squad/p1",
    );
  });

  it("makes the user fix a stored player the rules now refuse before saving", async () => {
    const update = updatePlayerReturns("p1");
    server.use(update.handler);
    await renderEdit(playerBody({ primaryPosition: null, heightCm: 300 }));
    await ready();

    submit();

    expect(errorOf(combobox(fields.primaryPosition))).toBe(he.squad.form.errors.required);
    expect(errorOf(input(fields.heightCm))).toBe(he.squad.form.errors.heightRange);
    expect(update.bodies).toEqual([]);
  });

  it("sends the full replacement with the loaded version, and shows the saved card", async () => {
    const update = updatePlayerReturns("p1");
    const {
      router,
      queryClient,
      server: backend,
    } = await renderEdit(playerBody({ version: 4, secondaryPosition: "RB", heightCm: 182 }));
    server.use(update.handler);
    await ready();
    // A list in the cache, to see it invalidated.
    queryClient.setQueryData([...SQUAD_PLAYERS_QUERY_KEY, { status: "active" }], []);
    type(fields.fullName, "Yossi Levy");
    type(fields.heightCm, "");
    // Hold the card's refetch, to see what the write's answer alone shows.
    const refetch = deferred();
    server.use(playerReturns(() => backend.player, { gate: refetch.promise }));

    submit();

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/squad/p1"));
    expect(update.bodies).toEqual([
      {
        fullName: "Yossi Levy",
        primaryPosition: "CB",
        secondaryPosition: "RB",
        jerseyNumber: 4,
        dateOfBirth: "1998-05-20",
        heightCm: null,
        weightKg: 78,
        preferredFoot: "RIGHT",
        medicalStatus: "FIT",
        version: 4,
      },
    ]);
    expect(router.state.historyAction).toBe("REPLACE");
    // The card shows the saved player at once, from the write's answer.
    expect(queryClient.getQueryData<Player>(playerQueryKey("p1"))).toMatchObject({
      fullName: "Yossi Levy",
      version: 5,
    });
    expect(
      await screen.findByRole("heading", { level: 2, name: "Yossi Levy" }),
    ).toBeInTheDocument();
    // Every squad query was invalidated: the lists refetch when next shown.
    expect(
      queryClient
        .getQueryCache()
        .find({ queryKey: [...SQUAD_PLAYERS_QUERY_KEY, { status: "active" }] })?.state
        .isInvalidated,
    ).toBe(true);
    refetch.resolve();
    // Back skips the form: it was replaced.
    await act(() => router.navigate(-1));
    expect(router.state.location.pathname).not.toBe("/app/squad/p1/edit");
  });

  it("waits for a fresh load instead of filling the form from an older cached copy", async () => {
    // The card's copy in the cache is version 1 / name A; the server has moved on to 2 / B.
    const fresh = deferred();
    const update = updatePlayerReturns("p1");
    server.use(
      refreshReturns("t1"),
      meReturns(),
      playerReturns(playerBody({ fullName: "Name B", version: 2 }), { gate: fresh.promise }),
      update.handler,
    );
    const { router, queryClient } = renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
    queryClient.setQueryData(playerQueryKey("p1"), playerBody({ fullName: "Name A", version: 1 }));

    await act(() => router.navigate("/app/squad/p1/edit"));
    await screen.findByRole("heading", { level: 1, name: he.squad.editPlayer });

    // No form while the fresh copy is on its way: the cached one may be out of date.
    expect(screen.queryByRole("textbox", { name: fields.fullName })).toBeNull();
    expect(screen.getByText(he.squad.player.loading)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: he.squad.form.submitEdit })).toBeNull();
    fresh.resolve();
    await ready();
    expect(input(fields.fullName)).toHaveValue("Name B");
    submit();
    await waitFor(() => expect(update.bodies).toHaveLength(1));
    expect(update.bodies[0]).toMatchObject({ fullName: "Name B", version: 2 });
  });

  it("shows the load error, not the cached copy, when the fresh load fails; a retry fills it", async () => {
    let fail = true;
    server.use(
      refreshReturns("t1"),
      meReturns(),
      http.get("/squad/players/:id", () =>
        fail
          ? apiError(500, "Internal Server Error", "An unexpected error occurred")
          : HttpResponse.json(playerBody({ fullName: "Name B", version: 2 })),
      ),
    );
    const { router, queryClient } = renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
    queryClient.setQueryData(playerQueryKey("p1"), playerBody({ fullName: "Name A", version: 1 }));

    await act(() => router.navigate("/app/squad/p1/edit"));

    expect(await screen.findByText(he.squad.player.loadError)).toBeInTheDocument();
    expect(screen.queryByRole("textbox", { name: fields.fullName })).toBeNull();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: he.squad.retry }));
    await ready();
    expect(input(fields.fullName)).toHaveValue("Name B");
  });

  it("doesn't overwrite what the user typed when the player is refetched, and keeps its version", async () => {
    const update = updatePlayerReturns("p1");
    const { queryClient, server: backend } = await renderEdit(playerBody({ version: 1 }));
    server.use(update.handler);
    await ready();
    type(fields.fullName, "Typed Name");

    backend.player = playerBody({ fullName: "Changed Elsewhere", jerseyNumber: 99, version: 2 });
    await act(() => queryClient.refetchQueries({ queryKey: playerQueryKey("p1") }));
    expect(queryClient.getQueryData<Player>(playerQueryKey("p1"))?.version).toBe(2);

    expect(input(fields.fullName)).toHaveValue("Typed Name");
    expect(input(fields.jerseyNumber)).toHaveValue("4");
    submit();
    await waitFor(() => expect(update.bodies).toHaveLength(1));
    // The version the shown values came from, never the newer one in the cache: the server
    // decides whether that's stale.
    expect(update.bodies[0]).toMatchObject({ fullName: "Typed Name", version: 1 });
  });

  it("on a stale version, keeps the input until the user loads the latest, then saves with its version", async () => {
    let stale = true;
    const update = updatePlayerReturns("p1", (body) =>
      stale
        ? conflict("STALE_VERSION")
        : Response.json(playerBody({ ...body, version: 6 } as Partial<Player>)),
    );
    const { router, server: backend } = await renderEdit(playerBody({ version: 3 }));
    server.use(update.handler);
    await ready();
    type(fields.fullName, "My Edit");

    submit();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(he.squad.form.stale.message);
    expect(alert).toHaveTextContent(he.squad.form.stale.hint);
    expect(input(fields.fullName)).toHaveValue("My Edit");

    backend.player = playerBody({ fullName: "Their Edit", weightKg: 90, version: 5 });
    fireEvent.click(screen.getByRole("button", { name: he.squad.form.stale.reload }));

    await waitFor(() => expect(input(fields.fullName)).toHaveValue("Their Edit"));
    expect(input(fields.weightKg)).toHaveValue("90");
    expect(screen.queryByRole("alert")).toBeNull();

    stale = false;
    type(fields.jerseyNumber, "8");
    submit();
    await waitFor(() => expect(router.state.location.pathname).toBe("/app/squad/p1"));
    expect(update.bodies.map((body) => body.version)).toEqual([3, 5]);
    expect(update.bodies[1]).toMatchObject({ fullName: "Their Edit", jerseyNumber: 8 });
  });

  it("says when the latest version can't be loaded", async () => {
    const { server: backend } = await renderEdit();
    server.use(updatePlayerReturns("p1", () => conflict("STALE_VERSION")).handler);
    await ready();
    type(fields.fullName, "My Edit");
    submit();
    await screen.findByRole("button", { name: he.squad.form.stale.reload });

    backend.player = playerBody({ id: "other" });
    fireEvent.click(screen.getByRole("button", { name: he.squad.form.stale.reload }));

    await waitFor(() =>
      expect(screen.getByRole("alert")).toHaveTextContent(he.squad.player.loadError),
    );
    expect(input(fields.fullName)).toHaveValue("My Edit");
  });

  it("on a released player, says so with a link to the card, and invalidates the squad", async () => {
    const { queryClient } = await renderEdit();
    server.use(updatePlayerReturns("p1", () => conflict("PLAYER_RELEASED")).handler);
    await ready();
    queryClient.setQueryData([...SQUAD_PLAYERS_QUERY_KEY, { status: "active" }], []);

    submit();

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(he.squad.form.released);
    expect(screen.getByRole("link", { name: he.squad.form.toCard })).toHaveAttribute(
      "href",
      "/app/squad/p1",
    );
    expect(
      queryClient
        .getQueryCache()
        .find({ queryKey: [...SQUAD_PLAYERS_QUERY_KEY, { status: "active" }] })?.state
        .isInvalidated,
    ).toBe(true);
  });

  it("puts a taken jersey number on its field", async () => {
    await renderEdit();
    server.use(updatePlayerReturns("p1", () => conflict("JERSEY_NUMBER_TAKEN")).handler);
    await ready();

    submit();

    await waitFor(() =>
      expect(errorOf(input(fields.jerseyNumber))).toBe(he.squad.form.errors.jerseyNumberTaken),
    );
  });

  it.each([
    [403, () => apiError(403, "Forbidden", "Access denied"), he.squad.noPermission],
    [404, () => apiError(404, "Not Found", "Player not found"), he.squad.player.notFound],
  ])("shows a %s on submit as an alert, keeping the input", async (_status, answer, text) => {
    await renderEdit();
    server.use(updatePlayerReturns("p1", answer).handler);
    await ready();
    type(fields.fullName, "Kept");

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(input(fields.fullName)).toHaveValue("Kept");
  });

  it("shows a released player no form, only a message and the way to the card", async () => {
    await renderEdit(playerBody({ active: false }));

    expect(await screen.findByText(he.squad.form.released)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: he.squad.form.toCard })).toHaveAttribute(
      "href",
      "/app/squad/p1",
    );
    expect(screen.queryByRole("button", { name: he.squad.form.submitEdit })).toBeNull();
  });

  it("says when the player doesn't exist", async () => {
    server.use(refreshReturns("t1"), meReturns(), playerReturns(playerBody()));
    renderWithProviders({ initialEntries: ["/app/squad/gone/edit"] });

    expect(await screen.findByText(he.squad.player.notFound)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: he.squad.form.submitEdit })).toBeNull();
  });
});

describe("the edit page's guard", () => {
  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as PermissionLevel[])(
    "shows %s the no-permission message and loads nothing",
    async (permissionLevel) => {
      server.use(refreshReturns("t1"), meReturns({ permissionLevel }));
      renderWithProviders({ initialEntries: ["/app/squad/p1/edit"] });

      expect(await screen.findByText(he.squad.noPermission)).toBeInTheDocument();
      expect(screen.getByRole("link", { name: he.squad.backToSquad })).toBeInTheDocument();
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(fetchedPaths()).toEqual(["/auth/refresh", "/auth/users/me"]);
    },
  );
});
