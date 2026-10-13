import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeAll, describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { PLAYER_STATS_QUERY_KEY } from "@/lib/matches/playerStats";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { watchLiveInsertion } from "@/test/liveRegion";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import { playerStatsBody, removeStatsLinkReturns } from "@/test/msw/playerStats";
import { server } from "@/test/msw/server";
import { conflict, deleteReturns, playerBody, playerReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([import("@/pages/squad/PlayerCardPage"), import("@/pages/squad/SquadPage")]),
);

const D = he.squad.dialog;
const L = D.statsLinked;
const player = playerBody({ fullName: "דני לוי" });
const other = playerBody({ id: "p2", fullName: "Avi Cohen" });
const deletedNotice = "דני לוי נמחק לצמיתות.";

/** GET /squad/players answering from `store.players` as it is now. */
function serverSquad(players: Player[]) {
  const store = { players };
  server.use(http.get("/squad/players", () => HttpResponse.json(store.players)));
  return store;
}

/**
 * The two hosts of the delete dialog (KAN-74 works the same from both): the player card, and the
 * squad table's row menu. Each logs in as an admin and returns the open delete dialog.
 */
const hosts = {
  card: async () => {
    server.use(refreshReturns("t1"), meReturns(), playerReturns(player));
    const squad = serverSquad([other]);
    const rendered = renderWithProviders({ initialEntries: [`/app/squad/${player.id}`] });
    await screen.findByRole("heading", { level: 2, name: player.fullName });
    fireEvent.click(screen.getByRole("button", { name: he.squad.actions.delete }));
    return { ...rendered, squad, dialog: await screen.findByRole("alertdialog") };
  },
  table: async () => {
    server.use(refreshReturns("t1"), meReturns());
    const squad = serverSquad([player, other]);
    const rendered = renderWithProviders({ initialEntries: ["/app/squad"] });
    await screen.findByRole("link", { name: player.fullName });
    fireEvent.click(
      screen.getByRole("button", { name: he.squad.actions.menu.replace("{{name}}", "דני לוי") }),
    );
    const menu = await screen.findByRole("menu");
    fireEvent.click(within(menu).getByRole("menuitem", { name: he.squad.actions.delete }));
    return { ...rendered, squad, dialog: await screen.findByRole("alertdialog") };
  },
};

const deleteButton = (dialog: HTMLElement) =>
  within(dialog).getByRole("button", { name: he.squad.actions.delete });
const unlinkButton = (dialog: HTMLElement) =>
  within(dialog).getByRole("button", { name: L.unlink });

/** Opens the dialog at `host` and gets the 409 PLAYER_STATS_LINKED from a first delete. */
async function refusedDelete(host: keyof typeof hosts) {
  const remove = deleteReturns(player.id, () => conflict("PLAYER_STATS_LINKED"));
  server.use(remove.handler);
  const opened = await hosts[host]();
  fireEvent.click(deleteButton(opened.dialog));
  await within(opened.dialog).findByText(L.title);
  return { ...opened, remove };
}

describe.each(["card", "table"] as const)("deleting a linked player from the %s", (host) => {
  it("explains the 409 PLAYER_STATS_LINKED and offers the unlink, never the generic failure", async () => {
    const { dialog } = await refusedDelete(host);

    expect(within(dialog).getByRole("alert")).toHaveTextContent(L.title);
    expect(within(dialog).getByText(L.text)).toBeInTheDocument();
    expect(unlinkButton(dialog)).toBeEnabled();
    expect(within(dialog).queryByText(D.failed)).toBeNull();
  });

  it("unlinks with one request, says so, and deletes only on a second click", async () => {
    const { dialog, remove, squad } = await refusedDelete(host);
    const unlink = removeStatsLinkReturns(player.id);
    server.use(unlink.handler);
    const watch = watchLiveInsertion(L.unlinked);

    fireEvent.click(unlinkButton(dialog));
    fireEvent.click(unlinkButton(dialog));

    expect(await within(dialog).findByText(L.unlinked)).toBeInTheDocument();
    watch.stop();
    expect(watch.result.intoExistingRegion).toBe(true);
    expect(within(dialog).queryByText(L.title)).toBeNull();
    expect(unlink.requests.count).toBe(1);
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(remove.requests.count).toBe(1);

    const deleted = deleteReturns(player.id, () => {
      squad.players = [other];
      return new HttpResponse(null, { status: 204 });
    });
    server.use(deleted.handler);
    fireEvent.click(deleteButton(dialog));

    expect(await screen.findByText(deletedNotice)).toBeInTheDocument();
    expect(deleted.requests.count).toBe(1);
    await waitFor(() => expect(screen.queryByRole("link", { name: player.fullName })).toBeNull());
  });

  it("disables both buttons while the unlink runs", async () => {
    const { dialog } = await refusedDelete(host);
    const gate = deferred<void>();
    server.use(removeStatsLinkReturns(player.id, undefined, gate.promise).handler);

    fireEvent.click(unlinkButton(dialog));

    await waitFor(() =>
      expect(within(dialog).getByRole("button", { name: L.unlinking })).toBeDisabled(),
    );
    expect(deleteButton(dialog)).toBeDisabled();
    gate.resolve();
    expect(await within(dialog).findByText(L.unlinked)).toBeInTheDocument();
    expect(deleteButton(dialog)).toBeEnabled();
  });

  it("shows a player that's gone (404 on the unlink) as not found", async () => {
    const { dialog } = await refusedDelete(host);
    server.use(
      removeStatsLinkReturns(player.id, () => apiError(404, "Not Found", "Player not found"))
        .handler,
    );

    fireEvent.click(unlinkButton(dialog));

    expect(await within(dialog).findByText(he.squad.player.notFound)).toBeInTheDocument();
  });

  it("shows a failed unlink (500) as the generic failure, the delete still possible", async () => {
    const { dialog } = await refusedDelete(host);
    server.use(
      removeStatsLinkReturns(player.id, () =>
        apiError(500, "Internal Server Error", "An unexpected error occurred"),
      ).handler,
    );

    fireEvent.click(unlinkButton(dialog));

    expect(await within(dialog).findByText(D.failed)).toBeInTheDocument();
    expect(deleteButton(dialog)).toBeEnabled();
    expect(unlinkButton(dialog)).toBeEnabled();
  });

  it("marks the cached player stats out of date after the unlink", async () => {
    const { dialog, queryClient } = await refusedDelete(host);
    queryClient.setQueryData(PLAYER_STATS_QUERY_KEY, playerStatsBody());
    server.use(removeStatsLinkReturns(player.id).handler);

    fireEvent.click(unlinkButton(dialog));

    await within(dialog).findByText(L.unlinked);
    expect(queryClient.getQueryState(PLAYER_STATS_QUERY_KEY)?.isInvalidated).toBe(true);
  });
});
