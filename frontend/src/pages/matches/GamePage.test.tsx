import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeAll, describe, expect, it, vi } from "vitest";
import i18n from "@/i18n/i18n";
import he from "@/i18n/locales/he.json";
import type { PermissionLevel } from "@/lib/auth/permissions";
import type { Fixtures, PlayedGameDetails } from "@/lib/matches/types";
import { deferred } from "@/test/deferred";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import {
  appearanceBody,
  cupGameDetailsBody,
  fixturesBody,
  fixturesReturns,
  gameDetailsBody,
  gameDetailsReturns,
} from "@/test/msw/matches";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([import("@/pages/matches/GamePage"), import("@/pages/matches/MatchesPage")]),
);

afterEach(() => {
  vi.restoreAllMocks();
});

const M = he.matches;
const G = he.matches.game;
const GAME_ID = gameDetailsBody().id;
const failure = () => apiError(500, "Internal Server Error", "An unexpected error occurred");

/**
 * Logged in as a VIEW_ONLY user (unless `level`) at the game page of `id`, the fixtures and the
 * game answering. Waits for the shell's <h1>; returns the game requests' count.
 */
async function renderGame({
  id = GAME_ID,
  fixtures = fixturesBody(),
  game,
  level = "VIEW_ONLY",
}: {
  id?: string;
  fixtures?: Parameters<typeof fixturesReturns>[0];
  game?: Parameters<typeof gameDetailsReturns>[0];
  level?: PermissionLevel;
} = {}) {
  const details = gameDetailsReturns(game);
  server.use(
    refreshReturns("t1"),
    meReturns({ permissionLevel: level }),
    fixturesReturns(fixtures),
    details.handler,
  );
  const rendered = renderWithProviders({ initialEntries: [`/app/matches/games/${id}`] });
  await screen.findByRole("heading", { level: 1, name: G.title });
  return { ...rendered, requests: details.requests };
}

/** Renders `game` and waits for its header. */
async function renderLoaded(game: Partial<PlayedGameDetails> = {}, fixtures?: Fixtures) {
  const rendered = await renderGame({ game: gameDetailsBody(game), fixtures });
  await screen.findByRole("heading", { level: 2, name: /מחזור/ });
  return rendered;
}

const scoreline = () => document.querySelector<HTMLElement>('[data-slot="scoreline"]')!;
const slot = (name: string) =>
  scoreline().querySelector<HTMLElement>(`[data-slot="${name}"]`)!.textContent;
const group = (heading: string) => screen.getByRole("list", { name: heading });
/** The <li> of the player whose (visible) name is `name`. */
const playerRow = (name: string) => screen.getByText(name).closest("li")!;
const eventsOf = (name: string) =>
  within(within(playerRow(name)).getByRole("list", { name: G.events })).getAllByRole("listitem");
const groupHeading = (role: keyof typeof G.role, count: number) =>
  i18n.t("matches.game.groupHeading", { label: G.role[role], count });

describe("the game page's header", () => {
  it("shows the competition and round, the badges, the score, date, time and last updated", async () => {
    await renderLoaded();

    expect(screen.getByRole("heading", { level: 2, name: "ליגה א' דרום · מחזור 6" })).toBeVisible();
    // The shell's <h1> is the only one.
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
    const header = screen.getByRole("region", { name: "ליגה א' דרום · מחזור 6" });
    expect(within(header).getByText(M.side.HOME)).toBeVisible();
    expect(within(header).getByText(M.result.WIN)).toBeVisible();
    expect(
      within(header).getByText(
        i18n.t("matches.dateWithWeekday", { weekday: "שבת", date: "05.09.2026" }),
      ),
    ).toBeVisible();
    expect(within(header).getByText("19:30")).toBeVisible();
    // 15:30Z on 12 Oct is 18:30 in Israel (summer time).
    expect(
      within(header).getByText(i18n.t("matches.lastUpdated", { time: "12.10.2026 18:30" })),
    ).toBeVisible();
    expect(
      within(header).getByText(i18n.t("matches.game.scopeNote", { competition: "ליגה א' דרום" })),
    ).toBeVisible();
  });

  it("lays a home game out home → away in the DOM, our team (from the fixtures) in bold", async () => {
    await renderLoaded();

    expect([slot("home-team"), slot("home-score"), slot("away-score"), slot("away-team")]).toEqual([
      "Hapoel Sderot",
      "3",
      "1",
      "Maccabi Yavne",
    ]);
    const order = [...scoreline().querySelectorAll("[data-slot]")].map((e) =>
      e.getAttribute("data-slot"),
    );
    expect(order).toEqual(["home-team", "home-score", "away-score", "away-team"]);
    expect(scoreline().querySelector('[data-slot="home-team"]')).toHaveClass("font-semibold");
    expect(scoreline().querySelector('[data-slot="away-team"]')).not.toHaveClass("font-semibold");
  });

  it("puts our team on the away side of an away game, each score beside its team", async () => {
    await renderLoaded({
      ownSide: "AWAY",
      opponentName: "Beitar Kiryat Gat",
      ownScore: 0,
      opponentScore: 2,
      result: "LOSS",
    });

    expect([slot("home-team"), slot("home-score"), slot("away-score"), slot("away-team")]).toEqual([
      "Beitar Kiryat Gat",
      "2",
      "0",
      "Hapoel Sderot",
    ]);
    expect(scoreline().querySelector('[data-slot="away-team"]')).toHaveClass("font-semibold");
    expect(screen.getByText(M.side.AWAY)).toBeVisible();
    expect(screen.getByText(M.result.LOSS)).toBeVisible();
  });

  it("names our team 'הקבוצה שלנו' when the fixtures fail, without an error of their own", async () => {
    await renderGame({ fixtures: failure });
    await screen.findByRole("heading", { level: 2, name: /מחזור/ });

    expect(slot("home-team")).toBe(M.ownTeamFallback);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(M.loadError)).not.toBeInTheDocument();
    expect(screen.queryByText(he.dashboard.nextMatch.loadError)).not.toBeInTheDocument();
  });

  it("names our team 'הקבוצה שלנו' when the fixtures have no team name", async () => {
    await renderLoaded({}, fixturesBody({ teamName: null }));

    expect(slot("home-team")).toBe(M.ownTeamFallback);
  });

  it("says the time isn't set without a kickoff time", async () => {
    await renderLoaded({ kickoffTime: null });

    expect(screen.getByText(M.timeNotSet)).toBeVisible();
  });

  it("goes back to the matches screen's games through a link", async () => {
    await renderLoaded();

    expect(screen.getByRole("link", { name: G.back })).toHaveAttribute("href", "/app/matches");
  });
});

describe("the game page's header for a cup game (KAN-86)", () => {
  const CUP_HEADING = `${M.competition.STATE_CUP} · סיבוב ו`;

  /** Renders a cup game's details and waits for its header. */
  async function renderCup(game: Partial<PlayedGameDetails> = {}) {
    const rendered = await renderGame({ game: cupGameDetailsBody(game) });
    return { ...rendered, header: await screen.findByRole("region", { name: CUP_HEADING }) };
  }

  /** The secondary line's stage: home score, then away score (in the DOM). */
  const stage = (header: HTMLElement, name: "extra-time" | "penalties") => {
    const element = header.querySelector(`[data-slot=decided-by] [data-slot=${name}]`)!;
    const scores = [...element.querySelectorAll("[data-slot^=stage-]")];
    expect(scores.map((e) => e.getAttribute("data-slot"))).toEqual([
      "stage-home-score",
      "stage-away-score",
    ]);
    return { element, scores: scores.map((e) => e.textContent) };
  };

  it("is headed with our cup label and the cup list's round, never the page's מחזור", async () => {
    const { header } = await renderCup();

    expect(screen.getByRole("heading", { level: 2, name: CUP_HEADING })).toBeVisible();
    expect(header).not.toHaveTextContent("מחזור");
    expect(header).not.toHaveTextContent("ווינר");
    expect(
      within(header).getByText(
        i18n.t("matches.game.scopeNote", { competition: M.competition.STATE_CUP }),
      ),
    ).toBeVisible();
  });

  it("shows our cup label alone when the round is missing", async () => {
    await renderGame({ game: cupGameDetailsBody({ cupRound: null }) });

    const heading = await screen.findByRole("heading", { level: 2, name: M.competition.STATE_CUP });
    expect(heading).toHaveTextContent(new RegExp(`^${M.competition.STATE_CUP}$`));
  });

  it("keeps the 90-minute score and shows the extra time and the shootout under it, home first", async () => {
    const { header } = await renderCup();

    expect([slot("home-score"), slot("away-score")]).toEqual(["2", "2"]);
    const extraTime = stage(header, "extra-time");
    const penalties = stage(header, "penalties");
    expect(extraTime.element).toHaveTextContent(M.decidedBy.EXTRA_TIME);
    expect(extraTime.scores).toEqual(["3", "3"]);
    expect(penalties.element).toHaveTextContent(M.decidedBy.PENALTIES);
    expect(penalties.scores).toEqual(["1", "3"]);
    expect(within(header).getByText(M.result.LOSS)).toBeVisible();
  });

  it("puts the opponent's shootout tally first for an away game", async () => {
    const { header } = await renderCup({ ownSide: "AWAY" });
    expect(stage(header, "penalties").scores).toEqual(["3", "1"]);
  });

  it("shows only the extra time for a game decided in extra time", async () => {
    const { header } = await renderCup({
      penalties: null,
      extraTime: { ownScore: 3, opponentScore: 2 },
      decidedBy: "EXTRA_TIME",
      result: "WIN",
    });

    expect(stage(header, "extra-time").scores).toEqual(["3", "2"]);
    expect(header.querySelector("[data-slot=penalties]")).toBeNull();
    expect(within(header).getByText(M.result.WIN)).toBeVisible();
  });

  it("shows the scores and no result badge without a result, with no console error", async () => {
    // The console guard fails the test on any console.error / warn.
    const { header } = await renderCup({ result: null });

    expect([slot("home-score"), slot("away-score")]).toEqual(["2", "2"]);
    for (const text of [M.result.WIN, M.result.DRAW, M.result.LOSS]) {
      expect(within(header).queryByText(text)).toBeNull();
    }
    expect(within(header).getByText(M.side.HOME)).toBeVisible();
  });
});

describe("the game page's header for a game not in the cup data", () => {
  it("is headed and scoped exactly as before, with no secondary line", async () => {
    await renderLoaded({ competitionType: null, cupRound: null });

    const header = screen.getByRole("region", { name: "ליגה א' דרום · מחזור 6" });
    expect(
      within(header).getByText(i18n.t("matches.game.scopeNote", { competition: "ליגה א' דרום" })),
    ).toBeVisible();
    expect(header.querySelector("[data-slot=decided-by]")).toBeNull();
  });

  it("treats a LEAGUE type like null: the page's heading and round", async () => {
    await renderLoaded({ competitionType: "LEAGUE", cupRound: null });
    expect(screen.getByRole("heading", { level: 2, name: "ליגה א' דרום · מחזור 6" })).toBeVisible();
  });
});

describe("the game page's lineup", () => {
  it("shows the three groups in order, with their counts", async () => {
    await renderLoaded();

    const lineup = screen.getByRole("region", { name: G.lineup });
    expect(
      within(lineup)
        .getAllByRole("heading", { level: 3 })
        .map((h) => h.textContent),
    ).toEqual([
      groupHeading("STARTER", 11),
      groupHeading("SUBSTITUTE", 3),
      groupHeading("UNUSED", 4),
    ]);
    expect(group(groupHeading("STARTER", 11)).children).toHaveLength(11);
  });

  it("keeps the response's order within a group (not by shirt number)", async () => {
    await renderLoaded();

    const starters = group(groupHeading("STARTER", 11));
    const rows = [...starters.children].map(
      (li) => li.querySelector('[aria-hidden="true"]')!.textContent,
    );
    expect(rows).toEqual(["1", "9", "4", "5", "2", "3", "6", "8", "10", "11", "7"]);
  });

  it("doesn't render an empty group", async () => {
    await renderLoaded({
      players: gameDetailsBody().players.filter((player) => player.role !== "UNUSED"),
    });

    expect(screen.getByRole("heading", { name: groupHeading("SUBSTITUTE", 3) })).toBeVisible();
    expect(screen.queryByText(new RegExp(G.role.UNUSED))).not.toBeInTheDocument();
  });

  it("says there's no lineup without players", async () => {
    await renderLoaded({ players: [] });

    expect(screen.getByText(G.noLineup)).toBeVisible();
    expect(screen.queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
  });

  it("shows the shirt number worn, '–' and its screen-reader text when it's missing", async () => {
    await renderLoaded();

    const gilRow = playerRow("Gil Levi");
    expect(within(gilRow).getByText("5")).toHaveAttribute("aria-hidden", "true");
    expect(within(gilRow).getByText(i18n.t("matches.game.shirtNumber", { number: 5 }))).toHaveClass(
      "sr-only",
    );
    const zoharRow = playerRow("Zohar Navon");
    expect(within(zoharRow).getByText("–")).toHaveAttribute("aria-hidden", "true");
    expect(within(zoharRow).getByText(G.shirtNumberUnknown)).toHaveClass("sr-only");
  });

  it("marks the goalkeepers 'GK' (in English) and the captain 'קפטן'", async () => {
    await renderLoaded();

    const gks = screen.getAllByText("GK");
    expect(gks).toHaveLength(2);
    expect(gks[0]).toHaveAttribute("lang", "en");
    expect(within(playerRow("Avi Golan")).getByText("GK")).toBeVisible();
    expect(within(playerRow("Uri Katz")).getByText("GK")).toBeVisible();
    expect(within(playerRow("Eli Peretz")).getByText(G.captain)).toBeVisible();
    expect(screen.getAllByText(G.captain)).toHaveLength(1);
  });

  it("shows a linked player by our name, linking to the card, with the released badge", async () => {
    await renderLoaded();

    const daniel = screen.getByRole("link", { name: "Daniel Mizrahi" });
    expect(daniel).toHaveAttribute("href", "/app/squad/p-9");
    expect(screen.queryByText("Dan Mizrahi")).not.toBeInTheDocument();
    expect(within(daniel.closest("li")!).queryByText(he.squad.released)).not.toBeInTheDocument();

    const itay = screen.getByRole("link", { name: "Itay Bitton" });
    expect(itay).toHaveAttribute("href", "/app/squad/p-3");
    expect(within(itay.closest("li")!).getByText(he.squad.released)).toBeVisible();
    // An unlinked player's name is plain text.
    expect(screen.queryByRole("link", { name: "Gil Levi" })).not.toBeInTheDocument();
  });

  it("says a masked name isn't shown on the site", async () => {
    await renderLoaded();

    expect(screen.getByText(G.nameHidden)).toHaveClass("italic");
  });

  it("never shows the squad's jersey number or a minutes-played figure", async () => {
    await renderLoaded();

    expect(screen.queryByText(/דקות משחק/)).not.toBeInTheDocument();
  });
});

describe("the game page's events", () => {
  const at = (kind: keyof typeof G.event, minute: number) =>
    i18n.t("matches.game.eventAt", { event: G.event[kind], minute });
  const noMinute = (kind: keyof typeof G.event) =>
    i18n.t("matches.game.eventNoMinute", { event: G.event[kind] });

  it("gives every kind its screen-reader text, in the response's order", async () => {
    await renderLoaded();

    const texts = (name: string) =>
      eventsOf(name).map((item) => item.querySelector(".sr-only")!.textContent);
    expect(texts("Daniel Mizrahi")).toEqual([at("GOAL", 23), at("GOAL", 93)]);
    expect(texts("Gil Levi")).toEqual([at("YELLOW", 34), at("SECOND_YELLOW", 81)]);
    expect(within(playerRow("Moti Hadad")).getByText(at("RED", 70))).toHaveClass("sr-only");
    expect(within(playerRow("Kfir Azulay")).getByText(at("SUB_OFF", 60))).toHaveClass("sr-only");
    expect(texts("Ran Tal")).toEqual([at("SUB_ON", 60), at("GOAL", 88), at("YELLOW", 90)]);
  });

  it("shows an unknown minute as the icon alone, read as 'דקה לא ידועה'", async () => {
    await renderLoaded();

    const [goal] = eventsOf("Nir Ohana");
    expect(within(goal).getByText(noMinute("GOAL"))).toHaveClass("sr-only");
    expect(goal.querySelector("bdi")).toBeNull();
    const [subOn] = eventsOf("Tal Ben David");
    expect(within(subOn).getByText(noMinute("SUB_ON"))).toHaveClass("sr-only");
  });

  it("shows the minute as the site gives it, 93′, in an LTR island hidden from screen readers", async () => {
    await renderLoaded();

    const late = eventsOf("Daniel Mizrahi")[1].querySelector("bdi")!;
    expect(late).toHaveAttribute("dir", "ltr");
    expect(late).toHaveAttribute("aria-hidden", "true");
    expect(late.textContent).toBe("93′");
    expect(document.body.textContent).not.toMatch(/90\s*\+\s*3/);
  });

  it("makes a second yellow one event (no separate red)", async () => {
    await renderLoaded();

    expect(eventsOf("Gil Levi")).toHaveLength(2);
    expect(within(playerRow("Gil Levi")).queryByText(at("RED", 81))).not.toBeInTheDocument();
  });

  it("explains every icon in a legend, with where the minutes come from", async () => {
    await renderLoaded();

    const legend = screen.getByRole("list", { name: G.legend });
    expect(
      within(legend)
        .getAllByRole("listitem")
        .map((item) => item.textContent),
    ).toEqual([
      G.event.GOAL,
      G.event.YELLOW,
      G.event.SECOND_YELLOW,
      G.event.RED,
      G.event.SUB_ON,
      G.event.SUB_OFF,
    ]);
    expect(screen.getByText(G.minutesNote)).toBeVisible();
  });
});

describe("the game page's states", () => {
  it("shows a busy skeleton until the game has loaded", async () => {
    const gate = deferred();
    await renderGame({
      game: async () => {
        await gate.promise;
        return Response.json(gameDetailsBody());
      },
    });

    expect(screen.getByRole("status")).toHaveTextContent(M.loading);
    expect(screen.getByRole("status").closest("[aria-busy]")).toHaveAttribute("aria-busy", "true");
    gate.resolve();
    await screen.findByRole("heading", { level: 2, name: /מחזור/ });
    expect(document.querySelector("[aria-busy]")).toBeNull();
  });

  it("waits for the fixtures to settle before showing the game", async () => {
    const gate = deferred();
    const { requests } = await renderGame({
      fixtures: async () => {
        await gate.promise;
        return Response.json(fixturesBody());
      },
    });
    await waitFor(() => expect(requests.count).toBe(1));
    // The game is in, the fixtures aren't: still loading, never the fallback name first.
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(screen.getByRole("status")).toHaveTextContent(M.loading);
    expect(screen.queryByText(M.ownTeamFallback)).not.toBeInTheDocument();

    gate.resolve();
    await screen.findByRole("heading", { level: 2, name: /מחזור/ });
    expect(slot("home-team")).toBe("Hapoel Sderot");
  });

  it("shows 'not found' on a 404, with a link back, and doesn't request it again", async () => {
    const { requests } = await renderGame({ id: "no-such-game" });

    expect(await screen.findByText(G.notFound)).toBeVisible();
    expect(screen.getByText(G.notFoundHint)).toBeVisible();
    const backLinks = screen.getAllByRole("link", { name: G.back });
    expect(backLinks).toHaveLength(2);
    backLinks.forEach((link) => expect(link).toHaveAttribute("href", "/app/matches"));
    expect(screen.queryByRole("button", { name: M.retry })).not.toBeInTheDocument();
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(requests.count).toBe(1);
    expect(requests.ids).toEqual(["no-such-game"]);
  });

  it("shows an error with a retry on a 500, which refetches and shows the game", async () => {
    let fail = true;
    const { requests } = await renderGame({
      game: () => (fail ? failure() : Response.json(gameDetailsBody())),
    });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(G.loadError);
    expect(screen.queryByText(G.notFound)).not.toBeInTheDocument();
    fail = false;
    fireEvent.click(within(alert).getByRole("button", { name: M.retry }));

    await screen.findByRole("heading", { level: 2, name: /מחזור/ });
    expect(requests.count).toBe(2);
  });

  it("encodes the id in the request", async () => {
    const { requests } = await renderGame({ id: "a%2Fb" });

    await screen.findByText(G.notFound);
    expect(requests.ids).toEqual(["a/b"]);
  });
});

describe("the game page's permissions and data", () => {
  it("shows everything to VIEW_ONLY, links included, and never writes", async () => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");
    await renderLoaded();

    expect(screen.getByRole("region", { name: G.lineup })).toBeVisible();
    expect(screen.getByRole("link", { name: "Daniel Mizrahi" })).toBeVisible();
    const writes = fetchSpy.mock.calls
      .map(([input, init]) => ({
        method: (init?.method ?? "GET").toUpperCase(),
        path: new URL(String(input), "http://localhost").pathname,
      }))
      .filter(({ method, path }) => method !== "GET" && path !== "/auth/refresh");
    expect(writes).toEqual([]);
    expect(screen.queryByRole("button", { name: he.matches.refresh.button })).toBeNull();
  });

  it("shows no football.org.il link or id, only links inside the app", async () => {
    await renderLoaded();

    expect(document.body.innerHTML).not.toContain("football.org.il");
    const hrefs = [...document.querySelectorAll("a[href]")].map((a) => a.getAttribute("href")!);
    expect(hrefs.length).toBeGreaterThan(0);
    hrefs.forEach((href) => expect(href).toMatch(/^\/app(\/|$)/));
  });

  it("keeps 'משחקים' the sidebar's current item", async () => {
    await renderLoaded();

    const nav = screen.getByRole("navigation");
    expect(within(nav).getByRole("link", { name: he.nav.matches })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(within(nav).getByRole("link", { name: he.nav.stats })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("sets the tab title", async () => {
    await renderLoaded();

    expect(document.title).toBe(`${G.title} · ${he.app.name}`);
  });
});

// A sanity check on the test data itself: every role and event kind is covered.
describe("gameDetailsBody", () => {
  it("covers every role and event kind", () => {
    const { players } = gameDetailsBody();
    expect(new Set(players.map((p) => p.role))).toEqual(
      new Set(["STARTER", "SUBSTITUTE", "UNUSED"]),
    );
    expect(new Set(players.flatMap((p) => p.events.map((e) => e.kind)))).toEqual(
      new Set(["GOAL", "YELLOW", "SECOND_YELLOW", "RED", "SUB_ON", "SUB_OFF"]),
    );
    expect(appearanceBody().events).toEqual([]);
  });
});
