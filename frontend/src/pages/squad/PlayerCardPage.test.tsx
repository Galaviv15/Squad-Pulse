import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, PermissionLevel } from "@/lib/auth/currentUser";
import { playerQueryKey } from "@/lib/squad/players";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { apiError, imageReturns, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { playerBody, playerReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
  vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:test/1");
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

const fetchedPaths = () =>
  fetchSpy.mock.calls.map(([input]) => new URL(String(input), "http://localhost").pathname);

/** Logged in at the card of `player` (or the given id), as `user`. */
function renderCard(
  player: Player = playerBody(),
  { user = {}, id = player.id }: { user?: Partial<CurrentUser>; id?: string } = {},
) {
  server.use(refreshReturns("t1"), meReturns(user), playerReturns(player));
  return renderWithProviders({ initialEntries: [`/app/squad/${id}`] });
}

const nameHeading = (name = "Yossi Levi") => screen.findByRole("heading", { level: 2, name });

const detailsList = () => document.querySelector("dl") as HTMLElement;

/** The details list's value for `label`. */
function detail(label: string) {
  const term = within(detailsList()).getByText(label, { selector: "dt" });
  return term.nextElementSibling as HTMLElement;
}

describe("the player card", () => {
  it("shows every field", async () => {
    vi.useFakeTimers({ toFake: ["Date"], now: new Date(2026, 9, 6, 12) });
    renderCard(
      playerBody({
        fullName: "דני לוי",
        jerseyNumber: 9,
        primaryPosition: "ST",
        secondaryPosition: "LW",
        dateOfBirth: "1998-05-20",
        heightCm: 181,
        weightKg: 76,
        preferredFoot: "LEFT",
        medicalStatus: "INJURED",
      }),
    );

    expect(await nameHeading("דני לוי")).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(he.squad.playerCard);
    const number = screen.getByText("#9");
    expect(number).toHaveAttribute("dir", "ltr");
    expect(number).toHaveClass("tabular-nums");
    const header = screen.getByRole("heading", { level: 2 }).closest("section")!;
    expect(within(header).getByText("ST")).toHaveAttribute("dir", "ltr");
    expect(within(header).getByText("LW")).toHaveAttribute("dir", "ltr");
    expect(within(header).getByText(he.squad.medical.INJURED)).toBeInTheDocument();
    expect(within(header).queryByText(he.squad.released)).toBeNull();

    expect(detail(he.squad.fields.dateOfBirth)).toHaveTextContent("20.05.1998 (גיל 28)");
    expect(within(detail(he.squad.fields.dateOfBirth)).getByText("20.05.1998")).toHaveAttribute(
      "dir",
      "ltr",
    );
    expect(detail(he.squad.fields.heightCm)).toHaveTextContent("181");
    expect(detail(he.squad.fields.weightKg)).toHaveTextContent("76");
    expect(detail(he.squad.fields.preferredFoot)).toHaveTextContent(he.squad.foot.LEFT);
    expect(detail(he.squad.fields.medicalStatus)).toHaveTextContent(he.squad.medical.INJURED);
    expect(detail(he.squad.fields.primaryPosition)).toHaveTextContent("ST");
    expect(detail(he.squad.fields.secondaryPosition)).toHaveTextContent("LW");
    expect(detail(he.squad.fields.jerseyNumber)).toHaveTextContent("9");
    expect(document.title).toBe(`${he.squad.playerCard} · SquadPulse`);
  });

  it("uses ״ (U+05F4) in the units", () => {
    expect(he.squad.fields.heightCm).toBe("גובה (ס״מ)");
    expect(he.squad.fields.weightKg).toBe("משקל (ק״ג)");
  });

  it("shows a muted dash for every missing value", async () => {
    renderCard(
      playerBody({
        jerseyNumber: null,
        primaryPosition: null,
        secondaryPosition: null,
        dateOfBirth: null,
        heightCm: null,
        weightKg: null,
        preferredFoot: null,
      }),
    );
    await nameHeading();

    for (const label of [
      he.squad.fields.dateOfBirth,
      he.squad.fields.heightCm,
      he.squad.fields.weightKg,
      he.squad.fields.preferredFoot,
      he.squad.fields.primaryPosition,
      he.squad.fields.secondaryPosition,
      he.squad.fields.jerseyNumber,
    ]) {
      expect(detail(label)).toHaveTextContent(/^—$/);
      expect(within(detail(label)).getByText("—")).toHaveClass("text-muted-foreground");
    }
    expect(screen.queryByText(/^#/)).toBeNull();
  });

  it("shows a dash, not a broken date, for a malformed stored date", async () => {
    renderCard(playerBody({ dateOfBirth: "20/05/1998" }));
    await nameHeading();

    expect(detail(he.squad.fields.dateOfBirth)).toHaveTextContent(/^—$/);
  });

  it("dims a released player's content, not its action area, and marks it released", async () => {
    renderCard(playerBody({ active: false }));
    await nameHeading();

    const header = screen.getByRole("heading", { level: 2 }).closest("section")!;
    expect(within(header).getByText(he.squad.released)).toBeInTheDocument();
    const content = screen.getByRole("heading", { level: 2 }).closest(".opacity-60");
    expect(content).not.toBeNull();
    const back = screen.getByRole("link", { name: he.squad.backToSquad });
    expect(back.closest(".opacity-60")).toBeNull();
    expect(detailsList().closest("section")).toHaveClass("opacity-60");
  });

  it.each(["ADMIN", "EDIT_FULL"] as PermissionLevel[])(
    "links to the edit form for %s on an active player",
    async (permissionLevel) => {
      renderCard(playerBody(), { user: { permissionLevel } });
      await nameHeading();

      expect(screen.getByRole("link", { name: he.squad.edit })).toHaveAttribute(
        "href",
        "/app/squad/p1/edit",
      );
      expect(screen.getByRole("link", { name: he.squad.backToSquad })).toHaveAttribute(
        "href",
        "/app/squad",
      );
    },
  );

  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as PermissionLevel[])(
    "has no edit link for %s",
    async (permissionLevel) => {
      renderCard(playerBody(), { user: { permissionLevel } });
      await nameHeading();

      expect(screen.queryByRole("link", { name: he.squad.edit })).toBeNull();
      expect(screen.getByRole("link", { name: he.squad.backToSquad })).toBeInTheDocument();
    },
  );

  it("has no edit link for a released player, even for an admin", async () => {
    renderCard(playerBody({ active: false }));
    await nameHeading();

    expect(screen.queryByRole("link", { name: he.squad.edit })).toBeNull();
  });

  it("requests the photo only when the player has one", async () => {
    renderCard(playerBody({ hasPhoto: false }));
    await nameHeading();
    expect(fetchedPaths()).toEqual(["/auth/refresh", "/auth/users/me", "/squad/players/p1"]);
  });

  it("shows the photo when the player has one", async () => {
    server.use(imageReturns("/squad/players/p1/photo"));
    const { container } = renderCard(playerBody({ hasPhoto: true }));
    await nameHeading();

    await waitFor(() => expect(container.querySelector("img")).not.toBeNull());
    expect(container.querySelector("img")).toHaveClass("size-24", "rounded-full", "object-cover");
    expect(fetchedPaths()).toContain("/squad/players/p1/photo");
  });

  it("encodes the id in the request", async () => {
    const paths: string[] = [];
    server.use(
      refreshReturns("t1"),
      meReturns(),
      http.get("/squad/players/:id", ({ request }) => {
        paths.push(new URL(request.url).pathname);
        return HttpResponse.json(playerBody({ id: "a b" }));
      }),
    );
    renderWithProviders({ initialEntries: ["/app/squad/a%20b"] });
    await nameHeading();

    expect(paths).toEqual(["/squad/players/a%20b"]);
  });
});

describe("the player card's states", () => {
  it("shows a loading status until the player arrives", async () => {
    const gate = deferred();
    server.use(
      refreshReturns("t1"),
      meReturns(),
      playerReturns(playerBody(), { gate: gate.promise }),
    );
    renderWithProviders({ initialEntries: ["/app/squad/p1"] });

    expect(await screen.findByText(he.squad.player.loading)).toHaveAttribute("role", "status");
    gate.resolve();
    expect(await nameHeading()).toBeInTheDocument();
    expect(screen.queryByText(he.squad.player.loading)).toBeNull();
  });

  it("says the player wasn't found on a 404, with a way back", async () => {
    renderCard(playerBody(), { id: "gone" });

    expect(await screen.findByText(he.squad.player.notFound)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: he.squad.backToSquad })).toHaveAttribute(
      "href",
      "/app/squad",
    );
    expect(screen.queryByText(he.routeError.message)).toBeNull();
  });

  it("offers a retry after another error, which loads the player", async () => {
    let fail = true;
    server.use(
      refreshReturns("t1"),
      meReturns(),
      http.get("/squad/players/:id", () =>
        fail
          ? apiError(500, "Internal Server Error", "An unexpected error occurred")
          : HttpResponse.json(playerBody()),
      ),
    );
    renderWithProviders({ initialEntries: ["/app/squad/p1"] });

    expect(await screen.findByText(he.squad.player.loadError)).toBeInTheDocument();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: he.squad.retry }));

    expect(await nameHeading()).toBeInTheDocument();
  });

  it("keeps showing the player when a background refetch fails", async () => {
    const { queryClient } = renderCard();
    await nameHeading();
    server.use(
      http.get("/squad/players/:id", () => apiError(500, "Internal Server Error", "Oops")),
    );

    await act(() => queryClient.refetchQueries({ queryKey: playerQueryKey("p1") }));

    expect(screen.getByRole("heading", { level: 2, name: "Yossi Levi" })).toBeInTheDocument();
    expect(screen.queryByText(he.squad.player.loadError)).toBeNull();
  });
});
