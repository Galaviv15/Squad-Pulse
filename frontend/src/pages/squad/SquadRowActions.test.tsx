import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { beforeAll, describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, PermissionLevel } from "@/lib/auth/currentUser";
import type { Player } from "@/lib/squad/types";
import { meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { playerBody, playerReturns, recordedPlayers } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([
    import("@/pages/squad/SquadPage"),
    import("@/pages/squad/PlayerCardPage"),
    import("@/pages/squad/EditPlayerPage"),
  ]),
);

async function renderSquad({
  players = [playerBody()],
  user = {},
}: { players?: Player[]; user?: Partial<CurrentUser> } = {}) {
  server.use(refreshReturns("t1"), meReturns(user), recordedPlayers(players).handler);
  const rendered = renderWithProviders({ initialEntries: ["/app/squad?status=all"] });
  await screen.findByRole("link", { name: players[0].fullName });
  return rendered;
}

const trigger = (name: string) =>
  screen.getByRole("button", { name: he.squad.actions.menu.replace("{{name}}", name) });

async function openMenu(name: string) {
  fireEvent.click(trigger(name));
  return screen.findByRole("menu");
}

const itemNames = (menu: HTMLElement) =>
  within(menu)
    .getAllByRole("menuitem")
    .map((item) => item.textContent);

describe("the row actions", () => {
  it("are a narrow last column whose cells aren't dimmed for a released player", async () => {
    await renderSquad({ players: [playerBody({ active: false })] });

    const cell = trigger("Yossi Levi").closest("td")!;
    expect(cell).toHaveAttribute("data-actions");
    expect(cell).toHaveClass("text-end");
    expect(cell.closest("tr")).toHaveAttribute("data-released");
    expect(cell.closest("tr")!.className).toContain("[&>td:not([data-actions])]:opacity-60");
  });

  it.each([
    ["ADMIN", true, ["open", "edit", "release", "delete"]],
    ["EDIT_FULL", true, ["open", "edit", "release"]],
    ["EDIT_PARTIAL", true, ["open"]],
    ["VIEW_ONLY", true, ["open"]],
    ["ADMIN", false, ["open", "reactivate", "delete"]],
    ["EDIT_FULL", false, ["open", "reactivate"]],
    ["EDIT_PARTIAL", false, ["open"]],
    ["VIEW_ONLY", false, ["open"]],
  ] as [PermissionLevel, boolean, string[]][])(
    "offer %s on an active (%s) player: %j",
    async (permissionLevel, active, keys) => {
      await renderSquad({ user: { permissionLevel }, players: [playerBody({ active })] });

      const labels: Record<string, string> = {
        open: he.squad.actions.open,
        edit: he.squad.edit,
        release: he.squad.actions.release,
        reactivate: he.squad.actions.reactivate,
        delete: he.squad.actions.delete,
      };
      expect(itemNames(await openMenu("Yossi Levi"))).toEqual(keys.map((key) => labels[key]));
    },
  );

  it("put delete last, after a separator, styled destructive", async () => {
    await renderSquad();
    const menu = await openMenu("Yossi Levi");

    const remove = within(menu).getByRole("menuitem", { name: he.squad.actions.delete });
    expect(remove).toHaveAttribute("data-variant", "destructive");
    expect(remove.previousElementSibling).toHaveAttribute("role", "separator");
    expect(within(menu).getAllByRole("separator")).toHaveLength(1);
  });

  it("open from the trigger without opening the player", async () => {
    const { router } = await renderSquad();

    await openMenu("Yossi Levi");

    expect(router.state.location.pathname).toBe("/app/squad");
    expect(trigger("Yossi Levi")).toHaveAttribute("aria-expanded", "true");
  });

  it("go to the card from 'open card'", async () => {
    server.use(playerReturns(playerBody()));
    const { router } = await renderSquad();
    const menu = await openMenu("Yossi Levi");

    fireEvent.click(within(menu).getByRole("menuitem", { name: he.squad.actions.open }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/squad/p1"));
    expect(
      await screen.findByRole("heading", { level: 2, name: "Yossi Levi" }),
    ).toBeInTheDocument();
  });

  it("go to the edit form from 'edit', not to the card (the click doesn't reach the row)", async () => {
    server.use(playerReturns(playerBody()));
    const { router } = await renderSquad();
    const navigations: string[] = [];
    router.subscribe((state) => navigations.push(state.location.pathname));
    const menu = await openMenu("Yossi Levi");

    fireEvent.click(within(menu).getByRole("menuitem", { name: he.squad.edit }));

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/squad/p1/edit"));
    expect(navigations).not.toContain("/app/squad/p1");
    expect(
      await screen.findByRole("heading", { level: 1, name: he.squad.editPlayer }),
    ).toBeInTheDocument();
  });

  it("don't open the player on a click anywhere in the portaled menu, not only on a link", async () => {
    // React bubbles the click through the portal to the row; the popup isn't a link or a button
    // (KAN-59's in-place items won't be links either), so only the row's DOM check stops it.
    const { router } = await renderSquad();
    const menu = await openMenu("Yossi Levi");

    fireEvent.click(menu);

    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(router.state.location.pathname).toBe("/app/squad");
  });

  it("are links, so the items carry their href", async () => {
    await renderSquad();
    const menu = await openMenu("Yossi Levi");

    expect(within(menu).getByRole("menuitem", { name: he.squad.actions.open })).toHaveAttribute(
      "href",
      "/app/squad/p1",
    );
    expect(within(menu).getByRole("menuitem", { name: he.squad.edit })).toHaveAttribute(
      "href",
      "/app/squad/p1/edit",
    );
  });

  it("open from the keyboard and close on Escape, back on the trigger", async () => {
    await renderSquad();
    const button = trigger("Yossi Levi");
    button.focus();

    fireEvent.keyDown(button, { key: "ArrowDown" });
    const menu = await screen.findByRole("menu");
    await waitFor(() => expect(within(menu).getAllByRole("menuitem")[0]).toHaveFocus());
    fireEvent.keyDown(document.activeElement!, { key: "ArrowDown" });
    await waitFor(() =>
      expect(within(menu).getByRole("menuitem", { name: he.squad.edit })).toHaveFocus(),
    );

    fireEvent.keyDown(document.activeElement!, { key: "Escape" });

    await waitFor(() => expect(screen.queryByRole("menu")).toBeNull());
    expect(button).toHaveFocus();
  });
});
