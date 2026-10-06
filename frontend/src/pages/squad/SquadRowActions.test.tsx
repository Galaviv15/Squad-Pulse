import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, PermissionLevel } from "@/lib/auth/currentUser";
import type { Player } from "@/lib/squad/types";
import { meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { playerBody, playerReturns, recordedPlayers } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

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

  it.each(["ADMIN", "EDIT_FULL"] as PermissionLevel[])(
    "offer open and edit to %s on an active player",
    async (permissionLevel) => {
      await renderSquad({ user: { permissionLevel } });

      expect(itemNames(await openMenu("Yossi Levi"))).toEqual([
        he.squad.actions.open,
        he.squad.edit,
      ]);
    },
  );

  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as PermissionLevel[])(
    "offer only open to %s",
    async (permissionLevel) => {
      await renderSquad({ user: { permissionLevel } });

      expect(itemNames(await openMenu("Yossi Levi"))).toEqual([he.squad.actions.open]);
    },
  );

  it("offer only open on a released player, even to an admin", async () => {
    await renderSquad({ players: [playerBody({ active: false })] });

    expect(itemNames(await openMenu("Yossi Levi"))).toEqual([he.squad.actions.open]);
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
