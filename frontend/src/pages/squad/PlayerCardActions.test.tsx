import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
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
  playerReturns,
  reactivateReturns,
  recordedPlayers,
  releaseReturns,
} from "@/test/msw/squad";
import { watchLiveInsertion } from "@/test/liveRegion";
import { renderWithProviders } from "@/test/render";

let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
});

afterEach(() => {
  vi.restoreAllMocks();
});

/** Every request sent, as "METHOD /path". */
const sent = () =>
  fetchSpy.mock.calls.map(([input, init]) => {
    const method = init?.method ?? (input instanceof Request ? input.method : "GET");
    return `${method} ${new URL(String(input instanceof Request ? input.url : input), "http://localhost").pathname}`;
  });

/**
 * The server's copy of the player: GET answers with it as it is now, so a write that changes it
 * (release, re-activate) is what a refetch sees.
 */
function serverPlayer(initial: Player) {
  const store = { player: initial };
  server.use(playerReturns(() => store.player));
  return store;
}

/** Logged in at the card of `player`, as `user`, after `entries` (earlier history). */
async function renderCard(
  player: Player,
  { user = {}, entries = [] }: { user?: Partial<CurrentUser>; entries?: string[] } = {},
) {
  server.use(refreshReturns("t1"), meReturns(user));
  const store = serverPlayer(player);
  const rendered = renderWithProviders({ initialEntries: [...entries, `/app/squad/${player.id}`] });
  await screen.findByRole("heading", { level: 2, name: player.fullName });
  return { ...rendered, store };
}

const actionArea = () =>
  screen.getByRole("link", { name: he.squad.backToSquad }).parentElement as HTMLElement;

const button = (name: string) => within(actionArea()).getByRole("button", { name });
const queryButton = (name: string) => within(actionArea()).queryByRole("button", { name });

const title = (key: string, name = "Yossi Levi") => key.replace("{{name}}", name);

async function openDialog(action: string) {
  fireEvent.click(button(action));
  return screen.findByRole("alertdialog");
}

const confirmButton = (dialog: HTMLElement, name: string) =>
  within(dialog).getByRole("button", { name });

describe("the card's release", () => {
  it("is offered with no re-activate on an active player, from EDIT_FULL up", async () => {
    await renderCard(playerBody(), { user: { permissionLevel: "EDIT_FULL" } });

    expect(button(he.squad.actions.release)).toBeInTheDocument();
    expect(queryButton(he.squad.actions.reactivate)).toBeNull();
  });

  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as const)(
    "isn't rendered at all for %s",
    async (permissionLevel) => {
      await renderCard(playerBody(), { user: { permissionLevel } });

      expect(queryButton(he.squad.actions.release)).toBeNull();
      expect(queryButton(he.squad.actions.reactivate)).toBeNull();
      expect(queryButton(he.squad.actions.delete)).toBeNull();
    },
  );

  it("asks first, in an alert dialog named after the player", async () => {
    await renderCard(playerBody());

    const dialog = await openDialog(he.squad.actions.release);

    expect(dialog).toHaveAccessibleName(title(he.squad.dialog.release.title));
    expect(dialog).toHaveAccessibleDescription(he.squad.dialog.release.text);
    // Modal: the rest of the page is hidden from assistive technology while it's open.
    expect(screen.queryByRole("heading", { level: 2, name: "Yossi Levi" })).toBeNull();
    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: he.squad.dialog.cancel })).toHaveFocus(),
    );
  });

  it("sends exactly the card's version, then shows the player released", async () => {
    const player = playerBody({ version: 3 });
    const { store } = await renderCard(player);
    const release = releaseReturns(player, (body) => {
      store.player = { ...store.player, active: false, version: Number(body.version) + 1 };
      return HttpResponse.json(store.player);
    });
    server.use(release.handler);
    const dialog = await openDialog(he.squad.actions.release);

    fireEvent.click(confirmButton(dialog, he.squad.actions.release));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(release.bodies).toEqual([{ version: 3 }]);
    expect(screen.getByRole("heading", { level: 2 }).closest("section")).toHaveTextContent(
      he.squad.released,
    );
    expect(button(he.squad.actions.reactivate)).toBeInTheDocument();
    expect(queryButton(he.squad.actions.release)).toBeNull();
    expect(screen.queryByRole("link", { name: he.squad.edit })).toBeNull();
    // Focus goes back to the button that replaced the one it was opened from.
    await waitFor(() => expect(button(he.squad.actions.reactivate)).toHaveFocus());
  });

  it("sends nothing when cancelled, or closed with Escape", async () => {
    await renderCard(playerBody());

    let dialog = await openDialog(he.squad.actions.release);
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.dialog.cancel }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    await waitFor(() => expect(button(he.squad.actions.release)).toHaveFocus());

    dialog = await openDialog(he.squad.actions.release);
    fireEvent.keyDown(dialog, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());

    expect(
      sent().filter((request) => !request.startsWith("GET") && request.includes("/squad/")),
    ).toEqual([]);
  });

  it("sends one request for a double click, and can't be closed while it runs", async () => {
    const player = playerBody();
    const { store } = await renderCard(player);
    const gate = deferred();
    const release = releaseReturns(player, async () => {
      await gate.promise;
      store.player = { ...store.player, active: false, version: 1 };
      return HttpResponse.json(store.player);
    });
    server.use(release.handler);
    const dialog = await openDialog(he.squad.actions.release);
    const confirm = confirmButton(dialog, he.squad.actions.release);

    fireEvent.click(confirm);
    fireEvent.click(confirm);

    await waitFor(() => expect(confirm).toHaveTextContent(he.squad.actions.releasing));
    expect(confirm).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: he.squad.dialog.cancel })).toBeDisabled();
    fireEvent.keyDown(dialog, { key: "Escape" });
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByRole("alertdialog")).toBe(dialog);

    await act(async () => gate.resolve());
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(release.bodies).toHaveLength(1);
  });

  it.each([
    ["STALE_VERSION", he.squad.dialog.stale],
    ["PLAYER_ALREADY_RELEASED", he.squad.dialog.alreadyReleased],
  ])("says %s in the dialog, and reloads the card from it", async (code, message) => {
    const player = playerBody();
    const { store } = await renderCard(player);
    let gets = 0;
    server.use(
      releaseReturns(player, () => {
        store.player = { ...store.player, active: false, version: 5 };
        return conflict(code);
      }).handler,
      playerReturns(() => store.player, { onRequest: () => (gets += 1) }),
    );
    const dialog = await openDialog(he.squad.actions.release);

    fireEvent.click(confirmButton(dialog, he.squad.actions.release));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
    fireEvent.click(within(dialog).getByRole("button", { name: he.squad.dialog.reload }));
    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    // The squad queries were refetched: the card now shows the server's (released) player.
    await waitFor(() => expect(button(he.squad.actions.reactivate)).toBeInTheDocument());
    expect(gets).toBeGreaterThan(0);
  });

  it.each([
    [apiError(403, "Forbidden", "Access denied"), he.squad.noPermission],
    [apiError(500, "Internal Server Error", "Unexpected"), he.squad.dialog.failed],
    [conflict("SOMETHING_NEW"), he.squad.dialog.failed],
  ])("shows a failure in the dialog and stays open (%#)", async (answer, message) => {
    const player = playerBody();
    await renderCard(player);
    server.use(releaseReturns(player, () => answer).handler);
    const dialog = await openDialog(he.squad.actions.release);

    fireEvent.click(confirmButton(dialog, he.squad.actions.release));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(message);
    expect(confirmButton(dialog, he.squad.actions.release)).toBeEnabled();
  });

  it("offers the squad when the player is gone (404)", async () => {
    const player = playerBody();
    await renderCard(player);
    server.use(
      releaseReturns(player, () => apiError(404, "Not Found", "Player not found")).handler,
    );
    const dialog = await openDialog(he.squad.actions.release);

    fireEvent.click(confirmButton(dialog, he.squad.actions.release));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(he.squad.player.notFound);
    expect(within(alert).getByRole("link", { name: he.squad.backToSquad })).toHaveAttribute(
      "href",
      "/app/squad",
    );
  });

  it("shows a failure when there's no response", async () => {
    const player = playerBody();
    await renderCard(player);
    server.use(releaseReturns(player, () => HttpResponse.error()).handler);
    const dialog = await openDialog(he.squad.actions.release);

    fireEvent.click(confirmButton(dialog, he.squad.actions.release));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(he.squad.dialog.failed);
  });
});

describe("the card's re-activation", () => {
  const released = (overrides: Partial<Player> = {}) =>
    playerBody({ active: false, version: 2, jerseyNumber: 4, ...overrides });

  const numberField = (dialog: HTMLElement) =>
    within(dialog).getByRole("textbox", { name: he.squad.fields.jerseyNumber });

  it("prefills the old number, focused, and sends it with the version", async () => {
    const player = released();
    const { store } = await renderCard(player);
    const reactivate = reactivateReturns(player, (body) => {
      store.player = { ...store.player, active: true, version: 3 };
      return HttpResponse.json({ ...store.player, jerseyNumber: body.jerseyNumber });
    });
    server.use(reactivate.handler);
    const dialog = await openDialog(he.squad.actions.reactivate);

    expect(dialog).toHaveAccessibleName(title(he.squad.dialog.reactivate.title));
    expect(numberField(dialog)).toHaveValue("4");
    expect(numberField(dialog)).toHaveAttribute("dir", "ltr");
    expect(numberField(dialog)).toHaveAccessibleDescription(he.squad.dialog.reactivate.hint);
    await waitFor(() => expect(numberField(dialog)).toHaveFocus());

    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(reactivate.bodies).toEqual([{ version: 2, jerseyNumber: 4 }]);
    expect(button(he.squad.actions.release)).toBeInTheDocument();
  });

  it("links the hint to the field, and keeps it linked after the error", async () => {
    await renderCard(released());
    const dialog = await openDialog(he.squad.actions.reactivate);
    const field = numberField(dialog);

    expect(field).toHaveAttribute("aria-describedby", "reactivate-jersey-number-hint");
    expect(field).not.toHaveAttribute("aria-invalid");
    expect(document.getElementById("reactivate-jersey-number-hint")).toHaveTextContent(
      he.squad.dialog.reactivate.hint,
    );

    fireEvent.change(field, { target: { value: "100" } });
    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    await waitFor(() => expect(field).toHaveAttribute("aria-invalid", "true"));
    expect(field).toHaveAttribute(
      "aria-describedby",
      "reactivate-jersey-number-error reactivate-jersey-number-hint",
    );
    expect(document.getElementById("reactivate-jersey-number-error")).toHaveTextContent(
      he.squad.form.errors.jerseyNumberRange,
    );
    expect(within(dialog).getByText(he.squad.dialog.reactivate.hint)).toBeVisible();
  });

  it("prefills nothing for a player without a number", async () => {
    await renderCard(released({ jerseyNumber: null }));

    const dialog = await openDialog(he.squad.actions.reactivate);

    expect(numberField(dialog)).toHaveValue("");
  });

  it("sends jerseyNumber: null when the field is cleared", async () => {
    const player = released();
    await renderCard(player);
    const reactivate = reactivateReturns(player);
    server.use(reactivate.handler);
    const dialog = await openDialog(he.squad.actions.reactivate);

    fireEvent.change(numberField(dialog), { target: { value: "  " } });
    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    await waitFor(() => expect(reactivate.bodies).toHaveLength(1));
    expect(reactivate.bodies[0]).toStrictEqual({ version: 2, jerseyNumber: null });
  });

  it("puts a taken number on the field, focused, and retries with a new one", async () => {
    const player = released();
    await renderCard(player);
    const reactivate = reactivateReturns(player, (body) =>
      body.jerseyNumber === 4
        ? conflict("JERSEY_NUMBER_TAKEN")
        : HttpResponse.json({ ...player, active: true, jerseyNumber: body.jerseyNumber }),
    );
    server.use(reactivate.handler);
    const dialog = await openDialog(he.squad.actions.reactivate);

    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    await waitFor(() =>
      expect(numberField(dialog)).toHaveAccessibleDescription(
        `${he.squad.form.errors.jerseyNumberTaken} ${he.squad.dialog.reactivate.hint}`,
      ),
    );
    expect(numberField(dialog)).toHaveAttribute("aria-invalid", "true");
    expect(numberField(dialog)).toHaveFocus();
    expect(within(dialog).queryByRole("alert")).toBeNull();

    fireEvent.change(numberField(dialog), { target: { value: "12" } });
    expect(numberField(dialog)).not.toHaveAttribute("aria-invalid");
    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    await waitFor(() => expect(screen.queryByRole("alertdialog")).toBeNull());
    expect(reactivate.bodies).toEqual([
      { version: 2, jerseyNumber: 4 },
      { version: 2, jerseyNumber: 12 },
    ]);
  });

  it.each(["0", "100", "7a", "1.5"])("sends nothing for %j and says why", async (value) => {
    await renderCard(released());
    const dialog = await openDialog(he.squad.actions.reactivate);
    const before = sent().length;

    fireEvent.change(numberField(dialog), { target: { value } });
    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    await waitFor(() =>
      expect(numberField(dialog)).toHaveAccessibleDescription(
        `${he.squad.form.errors.jerseyNumberRange} ${he.squad.dialog.reactivate.hint}`,
      ),
    );
    expect(numberField(dialog)).toHaveFocus();
    expect(sent().slice(before)).toEqual([]);
    // Live after the first submit: fixing it clears the error.
    fireEvent.change(numberField(dialog), { target: { value: "8" } });
    expect(numberField(dialog)).toHaveAccessibleDescription(he.squad.dialog.reactivate.hint);
  });

  it("puts a server 400 on the number on the field", async () => {
    const player = released();
    await renderCard(player);
    server.use(
      reactivateReturns(player, () =>
        apiError(400, "Bad Request", "Validation failed", ["jerseyNumber: must be less than 100"]),
      ).handler,
    );
    const dialog = await openDialog(he.squad.actions.reactivate);

    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    await waitFor(() =>
      expect(numberField(dialog)).toHaveAccessibleDescription(
        `${he.squad.form.errors.jerseyNumberRange} ${he.squad.dialog.reactivate.hint}`,
      ),
    );
  });

  it("says when the player is already active, with a reload", async () => {
    const player = released();
    await renderCard(player);
    server.use(reactivateReturns(player, () => conflict("PLAYER_ALREADY_ACTIVE")).handler);
    const dialog = await openDialog(he.squad.actions.reactivate);

    fireEvent.click(confirmButton(dialog, he.squad.actions.reactivate));

    const alert = await within(dialog).findByRole("alert");
    expect(alert).toHaveTextContent(he.squad.dialog.alreadyActive);
    expect(within(alert).getByRole("button", { name: he.squad.dialog.reload })).toBeInTheDocument();
  });
});

describe("the card's permanent delete", () => {
  it.each(["EDIT_FULL", "VIEW_ONLY"] as const)("isn't rendered for %s", async (permissionLevel) => {
    await renderCard(playerBody(), { user: { permissionLevel } });

    expect(queryButton(he.squad.actions.delete)).toBeNull();
  });

  it("is offered to an admin, for a released player too, styled destructive", async () => {
    await renderCard(playerBody({ active: false }));

    expect(button(he.squad.actions.delete)).toHaveClass("text-destructive");
  });

  it("asks first, in a destructive alert dialog", async () => {
    await renderCard(playerBody());

    const dialog = await openDialog(he.squad.actions.delete);

    expect(dialog).toHaveAccessibleName(title(he.squad.dialog.delete.title));
    expect(dialog).toHaveAccessibleDescription(he.squad.dialog.delete.text);
    expect(confirmButton(dialog, he.squad.actions.delete)).toHaveClass("bg-destructive");
  });

  it.each([
    ["204", undefined],
    ["404 (already gone)", () => apiError(404, "Not Found", "Player not found")],
  ])(
    "on a %s: replaces the card with the squad, shows the notice once, and never fetches the player again",
    async (_, answer) => {
      const player = playerBody({ fullName: "דני לוי" });
      const { router } = await renderCard(player, { entries: ["/app"] });
      const remove = deleteReturns(player.id, answer);
      server.use(remove.handler, recordedPlayers([]).handler);
      const dialog = await openDialog(he.squad.actions.delete);
      const before = sent().length;

      fireEvent.click(confirmButton(dialog, he.squad.actions.delete));

      const notice = await screen.findByText("דני לוי נמחק לצמיתות.");
      expect(notice.closest("[role=status]")).not.toBeNull();
      expect(router.state.location.pathname).toBe("/app/squad");
      expect(remove.requests.count).toBe(1);
      // The state is gone from the history entry: a reload or a return here shows no notice.
      await waitFor(() => expect(router.state.location.state).toBeNull());
      expect(screen.getByText("דני לוי נמחק לצמיתות.")).toBeInTheDocument();
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(sent().slice(before)).not.toContain("GET /squad/players/p1");

      // Back skips the deleted card: it was replaced.
      await act(() => router.navigate(-1));
      expect(router.state.location.pathname).toBe("/app");
      // Forward again, to the squad entry: rendered from history, without the notice.
      await act(() => router.navigate(1));
      expect(router.state.location.pathname).toBe("/app/squad");
      expect(await screen.findByRole("radiogroup")).toBeInTheDocument();
      expect(screen.queryByText("דני לוי נמחק לצמיתות.")).toBeNull();
      expect(sent().slice(before)).not.toContain("GET /squad/players/p1");
    },
  );

  it("announces the notice: its text goes into the squad page's status region once mounted", async () => {
    const player = playerBody({ fullName: "דני לוי" });
    await renderCard(player);
    server.use(deleteReturns(player.id).handler, recordedPlayers([]).handler);
    const dialog = await openDialog(he.squad.actions.delete);
    const watch = watchLiveInsertion("דני לוי נמחק לצמיתות.");

    fireEvent.click(confirmButton(dialog, he.squad.actions.delete));

    const notice = await screen.findByText("דני לוי נמחק לצמיתות.");
    watch.stop();
    expect(watch.result.intoExistingRegion).toBe(true);
    const region = notice.closest('[role="status"]')!;
    expect(region).toHaveTextContent("דני לוי נמחק לצמיתות.");
    // One live region: the notice inside isn't a second one.
    expect(region.querySelectorAll('[role="status"]')).toHaveLength(0);
  });

  it("keeps the dialog open on a 403", async () => {
    const player = playerBody();
    const { router } = await renderCard(player);
    server.use(deleteReturns(player.id, () => apiError(403, "Forbidden", "Access denied")).handler);
    const dialog = await openDialog(he.squad.actions.delete);

    fireEvent.click(confirmButton(dialog, he.squad.actions.delete));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(he.squad.noPermission);
    expect(router.state.location.pathname).toBe("/app/squad/p1");
  });
});
