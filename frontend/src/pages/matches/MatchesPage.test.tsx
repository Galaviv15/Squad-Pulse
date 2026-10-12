import { fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import he from "@/i18n/locales/he.json";
import type { PermissionLevel } from "@/lib/auth/permissions";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import {
  emptyFixtures,
  emptyLeagueTable,
  fixturesBody,
  fixturesReturns,
  gameBody,
  leagueTableBody,
  leagueTableReturns,
  playedGame,
} from "@/test/msw/matches";
import { server } from "@/test/msw/server";
import { playersReturn, summaryReturns } from "@/test/msw/squad";
import { dashboardMatchesReturn } from "@/test/msw/matches";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([import("@/pages/matches/MatchesPage"), import("@/pages/squad/SquadPage")]),
);

// 12 Oct 2026, 12:00 in Israel.
beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"], now: new Date("2026-10-12T09:00:00Z") });
});

afterEach(() => {
  vi.useRealTimers();
});

const M = he.matches;
const failure = () => apiError(500, "Internal Server Error", "An unexpected error occurred");

/**
 * Logged in as a VIEW_ONLY user (no refresh control, so no GET /matches/refresh: an unexpected
 * one fails the test) at `path`, with both reads answering. Waits for the shell's <h1>.
 */
async function renderMatches(
  path = "/app/matches",
  {
    fixtures = fixturesBody(),
    table = leagueTableBody(),
    level = "VIEW_ONLY",
  }: {
    fixtures?: Parameters<typeof fixturesReturns>[0];
    table?: Parameters<typeof leagueTableReturns>[0];
    level?: PermissionLevel;
  } = {},
) {
  server.use(
    refreshReturns("t1"),
    meReturns({ permissionLevel: level }),
    fixturesReturns(fixtures),
    leagueTableReturns(table),
  );
  const rendered = renderWithProviders({ initialEntries: [path] });
  await screen.findByRole("heading", { level: 1, name: he.nav.matches });
  return rendered;
}

const tab = (name: string) => screen.getByRole("radio", { name });
const games = async () =>
  within(await screen.findByRole("list", { name: M.gamesLabel })).getAllByRole("listitem");
const gameOf = async (opponent: string) =>
  (await games()).find((item) => within(item).queryByText(opponent) !== null)!;

/** `a` comes before `b` in the document. */
const before = (a: Element, b: Element) =>
  (a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0;

describe("the matches screen's tabs", () => {
  it("opens on the games by default", async () => {
    await renderMatches();

    expect(tab(M.tabs.games)).toBeChecked();
    expect(tab(M.tabs.table)).not.toBeChecked();
    expect(await games()).toHaveLength(4);
    expect(screen.getByRole("radiogroup", { name: M.tabsLabel })).toBeInTheDocument();
  });

  it("opens on the table with ?tab=table", async () => {
    await renderMatches("/app/matches?tab=table");

    expect(tab(M.tabs.table)).toBeChecked();
    expect(await screen.findByRole("table")).toBeInTheDocument();
    expect(screen.queryByRole("list", { name: M.gamesLabel })).toBeNull();
  });

  it("falls back to the games for an unknown tab", async () => {
    await renderMatches("/app/matches?tab=standings");

    expect(tab(M.tabs.games)).toBeChecked();
    expect(await games()).toHaveLength(4);
  });

  it("keeps the selected tab in the URL, and switching back drops it", async () => {
    const { router } = await renderMatches();
    await games();

    fireEvent.click(tab(M.tabs.table));

    expect(await screen.findByRole("table")).toBeInTheDocument();
    expect(router.state.location.search).toBe("?tab=table");

    fireEvent.click(tab(M.tabs.games));

    expect(await games()).toHaveLength(4);
    expect(router.state.location.search).toBe("");
  });

  it("comes back to the table on Back from another page", async () => {
    const { router } = await renderMatches();
    fireEvent.click(tab(M.tabs.table));
    await screen.findByRole("table");
    server.use(playersReturn([]));

    fireEvent.click(
      within(screen.getByRole("navigation", { name: he.shell.navLabel })).getByRole("link", {
        name: he.nav.squad,
      }),
    );
    await screen.findByRole("heading", { level: 1, name: he.nav.squad });
    await router.navigate(-1);

    expect(await screen.findByRole("heading", { level: 1, name: he.nav.matches })).toBeVisible();
    expect(tab(M.tabs.table)).toBeChecked();
    expect(router.state.location.search).toBe("?tab=table");
  });
});

describe("the games tab", () => {
  it("shows a played game's score and result badge", async () => {
    await renderMatches();
    const won = await gameOf("Maccabi Yavne");
    const lost = await gameOf("Beitar Kiryat Gat");

    expect(within(won).getByText(M.result.WIN)).toBeInTheDocument();
    expect(won.querySelector("[data-slot=home-score]")).toHaveTextContent(/^3$/);
    expect(won.querySelector("[data-slot=away-score]")).toHaveTextContent(/^1$/);
    expect(within(lost).getByText(M.result.LOSS)).toBeInTheDocument();
    expect(within(won).getByText("יום שבת, 05.09.2026")).toBeInTheDocument();
  });

  it("puts each score beside its team: home on the start side, for a home game", async () => {
    // We won 3–1 at home: Hapoel Sderot · 3 · – · 1 · Maccabi Yavne, in DOM order (RTL: right to left).
    await renderMatches();
    const game = await gameOf("Maccabi Yavne");
    const line = game.querySelector<HTMLElement>("[data-slot=scoreline]")!;
    const [homeTeam, homeScore, awayScore, awayTeam] = [
      "home-team",
      "home-score",
      "away-score",
      "away-team",
    ].map((slot) => line.querySelector(`[data-slot=${slot}]`)!);

    expect(homeTeam).toHaveTextContent("Hapoel Sderot");
    expect(homeScore).toHaveTextContent(/^3$/);
    expect(awayScore).toHaveTextContent(/^1$/);
    expect(awayTeam).toHaveTextContent("Maccabi Yavne");
    expect(before(homeTeam, homeScore)).toBe(true);
    expect(before(homeScore, awayScore)).toBe(true);
    expect(before(awayScore, awayTeam)).toBe(true);
    expect(homeTeam).toHaveClass("font-semibold");
  });

  it("puts the opponent and its score first for an away game", async () => {
    // We lost 0–2 away: Beitar Kiryat Gat · 2 · – · 0 · Hapoel Sderot.
    await renderMatches();
    const line = (await gameOf("Beitar Kiryat Gat")).querySelector<HTMLElement>(
      "[data-slot=scoreline]",
    )!;
    const slot = (name: string) => line.querySelector(`[data-slot=${name}]`)!;

    expect(slot("home-team")).toHaveTextContent("Beitar Kiryat Gat");
    expect(slot("home-score")).toHaveTextContent(/^2$/);
    expect(slot("away-score")).toHaveTextContent(/^0$/);
    expect(slot("away-team")).toHaveTextContent("Hapoel Sderot");
    expect(before(slot("home-team"), slot("home-score"))).toBe(true);
    expect(before(slot("home-score"), slot("away-score"))).toBe(true);
    expect(before(slot("away-score"), slot("away-team"))).toBe(true);
    expect(slot("away-team")).toHaveClass("font-semibold");
    // Never one "x–y" string.
    for (const element of line.querySelectorAll("*")) {
      expect(element.textContent).not.toMatch(/^\s*\d+\s*[–-]\s*\d+\s*$/);
    }
  });

  it("highlights only the next game, and shows upcoming ones without a score", async () => {
    await renderMatches();
    const next = await gameOf("Hapoel Ashdod");
    const later = await gameOf("Ironi Ofakim");

    expect(next).toHaveAttribute("data-next");
    expect(within(next).getByText(M.next)).toBeInTheDocument();
    expect(later).not.toHaveAttribute("data-next");
    expect(within(later).queryByText(M.next)).toBeNull();
    expect(screen.getAllByText(M.next)).toHaveLength(1);
    expect(next.querySelector("[data-slot=home-score]")).toBeNull();
    expect(within(next).getByText(M.versus)).toBeInTheDocument();
    expect(within(later).getByText(M.side.AWAY)).toBeInTheDocument();
  });

  it('shows a past game without a result as "no result", never a score', async () => {
    await renderMatches("/app/matches", {
      fixtures: fixturesBody({
        games: [
          gameBody({ date: "2026-10-03", opponentName: "Postponed FC", kickoffTime: null }),
          gameBody({ date: "2026-10-17" }),
        ],
      }),
    });
    const postponed = await gameOf("Postponed FC");

    expect(within(postponed).getByText(M.noResult)).toBeInTheDocument();
    expect(within(postponed).getByText(M.timeNotSet)).toBeInTheDocument();
    expect(postponed.querySelector("[data-slot=home-score]")).toBeNull();
    expect(postponed).not.toHaveAttribute("data-next");
    expect(await gameOf("Hapoel Ashdod")).toHaveAttribute("data-next");
  });

  it("names our team generically when the site's name is missing", async () => {
    await renderMatches("/app/matches", {
      fixtures: fixturesBody({ teamName: null, games: [playedGame()] }),
    });

    expect(within(await gameOf("Hapoel Ashdod")).getByText(M.ownTeamFallback)).toBeInTheDocument();
  });

  it("shows the games' last-updated time in Israel time", async () => {
    await renderMatches();

    expect(await screen.findByText("עודכן לאחרונה: 12.10.2026 18:30")).toBeInTheDocument();
  });
});

describe("the table tab", () => {
  it("shows the full table, the round and our row, inside a sideways scroll container", async () => {
    await renderMatches("/app/matches?tab=table");
    const table = await screen.findByRole("table");
    const rows = within(table).getAllByRole("row");

    expect(
      within(rows[0])
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual([
      M.table.position,
      M.table.team,
      M.table.played,
      M.table.won,
      M.table.drawn,
      M.table.lost,
      M.table.goals,
      M.table.goalDifference,
      M.table.points,
    ]);
    expect(rows).toHaveLength(15);
    expect(
      within(rows[1])
        .getAllByRole("cell")
        .map((cell) => cell.textContent),
    ).toEqual(["1", "Team 1", "6", "3", "1", "2", "10:7", "+3", "29"]);
    const own = rows.filter((row) => row.getAttribute("aria-current") === "true");
    expect(own).toHaveLength(1);
    expect(own[0]).toHaveTextContent(`Hapoel Sderot ${M.ownTeamMarker}`);
    expect(own[0]).toHaveClass("font-semibold");
    expect(screen.getByRole("heading", { level: 2, name: "מחזור 6" })).toBeInTheDocument();
    expect(screen.getByText("עודכן לאחרונה: 11.10.2026 12:05")).toBeInTheDocument();
    expect(table.parentElement).toHaveClass("overflow-x-auto");
  });

  it("shows a negative goal difference with its sign, and no round when there's none", async () => {
    await renderMatches("/app/matches?tab=table", {
      table: leagueTableBody({
        round: null,
        rows: [
          {
            ...leagueTableBody().rows[0],
            goalsFor: 3,
            goalsAgainst: 8,
            goalDifference: -5,
          },
        ],
      }),
    });
    const rows = within(await screen.findByRole("table")).getAllByRole("row");

    expect(within(rows[1]).getByText("-5")).toHaveAttribute("dir", "ltr");
    expect(screen.queryByRole("heading", { level: 2 })).toBeNull();
  });
});

describe("the matches screen's states", () => {
  it.each([
    ["NOT_MAPPED", M.notMapped],
    ["NO_DATA", M.noData],
  ] as const)("shows %s on both tabs, without a last-updated line", async (state, text) => {
    await renderMatches("/app/matches", {
      fixtures: emptyFixtures(state),
      table: emptyLeagueTable(state),
    });

    expect(await screen.findByText(text)).toBeInTheDocument();
    fireEvent.click(tab(M.tabs.table));
    expect(await screen.findByText(text)).toBeInTheDocument();
    expect(screen.queryByText(/^עודכן לאחרונה/)).toBeNull();
  });

  it("warns about a mapping problem on both tabs, and still shows the data", async () => {
    await renderMatches("/app/matches", {
      fixtures: fixturesBody({ mappingProblem: "TEAM_NOT_IN_STANDINGS" }),
      table: leagueTableBody({ mappingProblem: "TEAM_NOT_IN_STANDINGS" }),
    });

    expect(await screen.findByText(M.mappingProblem)).toBeInTheDocument();
    expect(await games()).toHaveLength(4);
    fireEvent.click(tab(M.tabs.table));
    expect(await screen.findByText(M.mappingProblem)).toBeInTheDocument();
    expect(await screen.findByRole("table")).toBeInTheDocument();
  });

  it("shows a read's error with a retry", async () => {
    await renderMatches("/app/matches", { fixtures: failure, table: failure });

    expect(await screen.findByText(he.dashboard.nextMatch.loadError)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: M.retry })).toBeInTheDocument();
    fireEvent.click(tab(M.tabs.table));
    expect(await screen.findByText(he.dashboard.leagueTable.loadError)).toBeInTheDocument();
  });
});

describe("the sidebar's matches item", () => {
  it("is a live link to the matches screen", async () => {
    server.use(
      refreshReturns("t1"),
      meReturns({ permissionLevel: "VIEW_ONLY" }),
      summaryReturns(),
      ...dashboardMatchesReturn(),
    );
    const { router } = renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });

    fireEvent.click(
      within(screen.getByRole("navigation", { name: he.shell.navLabel })).getByRole("link", {
        name: he.nav.matches,
      }),
    );

    expect(await screen.findByRole("heading", { level: 1, name: he.nav.matches })).toBeVisible();
    expect(router.state.location.pathname).toBe("/app/matches");
    expect(document.title).toBe(`${he.nav.matches} · SquadPulse`);
    // The coming-soon group no longer lists it.
    expect(
      within(screen.getByRole("list", { name: he.shell.comingSoon.label })).queryByText(
        he.nav.matches,
      ),
    ).toBeNull();
  });
});
