import { fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import he from "@/i18n/locales/he.json";
import { apiError, loggedIn } from "@/test/msw/auth";
import {
  cupGame,
  cupGameWithoutResult,
  emptyFixtures,
  emptyLeagueTable,
  fixturesBody,
  fixturesReturns,
  gameBody,
  leagueTableBody,
  leagueTableReturns,
  playedGame,
  refreshStatusReturns,
} from "@/test/msw/matches";
import { server } from "@/test/msw/server";
import { summaryBody, summaryReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

// Load the lazy page's code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() => import("@/pages/matches/MatchesPage"));

// 12 Oct 2026, 12:00 in Israel.
const NOW = new Date("2026-10-12T09:00:00Z");

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"], now: NOW });
});

afterEach(() => {
  vi.useRealTimers();
});

const failure = () => apiError(500, "Internal Server Error", "An unexpected error occurred");

type FixturesAnswer = Parameters<typeof fixturesReturns>[0];
type TableAnswer = Parameters<typeof leagueTableReturns>[0];

/** Logged in at /app with the summary and both match reads answering; waits for the shell. */
async function renderDashboard(
  fixtures: FixturesAnswer = fixturesBody(),
  table: TableAnswer = leagueTableBody(),
  summary: Parameters<typeof summaryReturns>[0] = summaryBody(),
) {
  server.use(
    ...loggedIn(),
    summaryReturns(summary),
    fixturesReturns(fixtures),
    leagueTableReturns(table),
  );
  const rendered = renderWithProviders({ initialEntries: ["/app"] });
  await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
  return rendered;
}

function cardOf(title: string) {
  const card = screen
    .getByRole("heading", { level: 2, name: title })
    .closest<HTMLElement>("[data-slot=card]");
  expect(card).not.toBeNull();
  return card!;
}

const nextMatchCard = () => cardOf(he.dashboard.nextMatch.title);
const tableCard = () => cardOf(he.dashboard.leagueTable.title);
const scrollContainer = () =>
  tableCard().querySelector<HTMLElement>("[data-slot=league-table-scroll]");

const M = he.matches;

describe("the next-match card", () => {
  it("names the competition of a league game that is next", async () => {
    await renderDashboard();
    const card = nextMatchCard();

    expect(await within(card).findByText("Hapoel Ashdod")).toBeInTheDocument();
    expect(card.querySelector("[data-slot=competition]")).toHaveTextContent(
      new RegExp(`^${M.competition.LEAGUE}$`),
    );
  });

  it("shows a cup game that is next with the cup and its round, past a played one without a result", async () => {
    await renderDashboard(
      fixturesBody({
        games: [
          cupGameWithoutResult({ date: "2026-10-12", opponentName: "Played Today" }),
          cupGame({ date: "2026-10-14", opponentName: "Cup Opponent", round: "ז" }),
          gameBody({ date: "2026-10-17", opponentName: "Hapoel Ashdod" }),
        ],
      }),
    );
    const card = nextMatchCard();

    expect(await within(card).findByText("Cup Opponent")).toBeInTheDocument();
    expect(within(card).getByText(`${M.competition.STATE_CUP} · סיבוב ז`)).toBeInTheDocument();
    expect(within(card).getByText(M.side.HOME)).toBeInTheDocument();
    expect(within(card).queryByText("Played Today")).toBeNull();
    expect(within(card).queryByText("Hapoel Ashdod")).toBeNull();
  });

  it("shows the next home game: opponent, home, date with weekday, time, venue, last updated", async () => {
    await renderDashboard();
    const card = nextMatchCard();

    expect(await within(card).findByText("Hapoel Ashdod")).toBeInTheDocument();
    expect(within(card).getByText(M.side.HOME)).toBeInTheDocument();
    // 17 Oct 2026 is a Saturday.
    expect(within(card).getByText("יום שבת, 17.10.2026")).toBeInTheDocument();
    expect(within(card).getByText("20:00")).toBeInTheDocument();
    expect(within(card).getByText("Sderot Municipal")).toBeInTheDocument();
    // 15:30 UTC is 18:30 in Israel.
    expect(within(card).getByText("עודכן לאחרונה: 12.10.2026 18:30")).toBeInTheDocument();
    // Played games are never the next one.
    expect(within(card).queryByText("Maccabi Yavne")).toBeNull();
  });

  it("shows an away game without a time or a venue", async () => {
    await renderDashboard(
      fixturesBody({
        games: [
          gameBody({
            date: "2026-10-13",
            ownSide: "AWAY",
            opponentName: "Ironi Ofakim",
            kickoffTime: null,
            venue: null,
          }),
        ],
      }),
    );
    const card = nextMatchCard();

    expect(await within(card).findByText("Ironi Ofakim")).toBeInTheDocument();
    expect(within(card).getByText(M.side.AWAY)).toBeInTheDocument();
    expect(within(card).getByText(M.timeNotSet)).toBeInTheDocument();
    expect(within(card).queryByText(M.venueLabel)).toBeNull();
  });

  it("takes today's unplayed game as the next one, and skips a past game without a result", async () => {
    await renderDashboard(
      fixturesBody({
        games: [
          gameBody({ date: "2026-10-11", opponentName: "Postponed FC" }),
          gameBody({ date: "2026-10-12", opponentName: "Today FC", kickoffTime: "10:00" }),
          gameBody({ date: "2026-10-17" }),
        ],
      }),
    );

    expect(await within(nextMatchCard()).findByText("Today FC")).toBeInTheDocument();
    expect(within(nextMatchCard()).queryByText("Postponed FC")).toBeNull();
  });

  it("says so when no game is upcoming", async () => {
    await renderDashboard(fixturesBody({ games: [playedGame({ date: "2026-09-05" })] }));

    expect(
      await within(nextMatchCard()).findByText(he.dashboard.nextMatch.none),
    ).toBeInTheDocument();
    expect(within(nextMatchCard()).getByText(/^עודכן לאחרונה/)).toBeInTheDocument();
  });

  it("links to the matches screen's games", async () => {
    const { router } = await renderDashboard();
    server.use(refreshStatusReturns().handler);
    const link = within(nextMatchCard()).getByRole("link", {
      name: he.dashboard.nextMatch.toMatches,
    });
    expect(link).toHaveAttribute("href", "/app/matches");

    fireEvent.click(link);

    expect(await screen.findByRole("heading", { level: 1, name: he.nav.matches })).toBeVisible();
    expect(router.state.location.search).toBe("");
    expect(await screen.findByRole("radio", { name: M.tabs.games })).toBeChecked();
  });
});

describe("Israel's today, not the browser's", () => {
  let originalTz: string | undefined;
  beforeEach(() => {
    // A browser in New York: at 22:30 UTC on 16 Oct it's still the 16th there, the 17th in Israel.
    originalTz = process.env.TZ;
    process.env.TZ = "America/New_York";
    vi.setSystemTime(new Date("2026-10-16T22:30:00Z"));
  });
  afterEach(() => {
    process.env.TZ = originalTz;
  });

  it("skips a game of the 16th once the 16th is over in Israel", async () => {
    await renderDashboard(
      fixturesBody({
        games: [
          gameBody({ date: "2026-10-16", opponentName: "Yesterday FC" }),
          gameBody({ date: "2026-10-17", opponentName: "Today FC" }),
        ],
      }),
    );

    expect(await within(nextMatchCard()).findByText("Today FC")).toBeInTheDocument();
    expect(within(nextMatchCard()).queryByText("Yesterday FC")).toBeNull();
  });
});

describe("the league-table card", () => {
  it("renders every row inside the scroll container, with our row marked", async () => {
    await renderDashboard();
    const card = tableCard();
    await within(card).findByText("Team 1");
    const container = scrollContainer()!;

    const rows = within(container).getAllByRole("row");
    // The header and all 14 teams, in the container.
    expect(rows).toHaveLength(15);
    expect(
      within(rows[0])
        .getAllByRole("columnheader")
        .map((cell) => cell.textContent),
    ).toEqual([M.table.position, M.table.team, M.table.played, M.table.points]);
    const own = rows.filter((row) => row.getAttribute("aria-current") === "true");
    expect(own).toHaveLength(1);
    expect(own[0]).toHaveTextContent(`Hapoel Sderot ${M.ownTeamMarker}`);
    expect(own[0]).toHaveClass("font-semibold");
    expect(within(own[0]).getByText(M.ownTeamMarker)).toHaveClass("sr-only");
    expect(within(card).getByText("עודכן לאחרונה: 11.10.2026 12:05")).toBeInTheDocument();
  });

  it("scrolls only its container so our row is centred, never the page", async () => {
    const ROW = 36;
    const TOP = 100;
    vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockImplementation(function (
      this: HTMLElement,
    ) {
      let top = 0;
      let height = 0;
      if (this.dataset.slot === "league-table-scroll") {
        [top, height] = [TOP, 216];
      } else if (this.tagName === "THEAD") {
        [top, height] = [TOP, ROW];
      } else if (this instanceof HTMLTableRowElement && this.parentElement?.tagName === "TBODY") {
        [top, height] = [TOP + ROW + this.sectionRowIndex * ROW, ROW];
      }
      return DOMRect.fromRect({ x: 0, y: top, width: 300, height });
    });
    vi.spyOn(HTMLElement.prototype, "clientHeight", "get").mockImplementation(function (
      this: HTMLElement,
    ) {
      return this.dataset.slot === "league-table-scroll" ? 216 : 0;
    });
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;
    const scrollTo = vi.spyOn(window, "scrollTo").mockImplementation(() => {});

    try {
      await renderDashboard();
      await within(tableCard()).findByText("Team 1");

      // Our row (9th) is 8 rows below the first: 36 + 8 × 36 = 324px into the content. Centred in
      // the 180px below the sticky header: 324 − 36 − (180 − 36) / 2 = 216.
      expect(scrollContainer()!.scrollTop).toBe(216);
      expect(scrollIntoView).not.toHaveBeenCalled();
      expect(scrollTo).not.toHaveBeenCalled();
    } finally {
      delete (Element.prototype as Partial<Element>).scrollIntoView;
    }
  });

  it("starts at the top without an own row", async () => {
    await renderDashboard(
      fixturesBody(),
      leagueTableBody({
        mappingProblem: "TEAM_NOT_IN_STANDINGS",
        rows: leagueTableBody().rows.map((row) => ({ ...row, ownTeam: false })),
      }),
    );
    await within(tableCard()).findByText("Team 1");

    expect(scrollContainer()!.scrollTop).toBe(0);
    expect(within(scrollContainer()!).queryByRole("row", { current: true })).toBeNull();
  });

  it("warns about a mapping problem, and still shows the data", async () => {
    await renderDashboard(
      fixturesBody({ mappingProblem: "TEAM_NOT_IN_STANDINGS" }),
      leagueTableBody({ mappingProblem: "TEAM_NOT_IN_STANDINGS" }),
    );

    expect(await within(tableCard()).findByText(M.mappingProblem)).toBeInTheDocument();
    expect(within(tableCard()).getByText("Team 1")).toBeInTheDocument();
    expect(within(nextMatchCard()).getByText(M.mappingProblem)).toBeInTheDocument();
    expect(within(nextMatchCard()).getByText("Hapoel Ashdod")).toBeInTheDocument();
  });

  it("links to the matches screen's table tab", async () => {
    const { router } = await renderDashboard();
    server.use(refreshStatusReturns().handler);
    const link = within(tableCard()).getByRole("link", { name: he.dashboard.leagueTable.toTable });
    expect(link).toHaveAttribute("href", "/app/matches?tab=table");

    fireEvent.click(link);

    expect(await screen.findByRole("radio", { name: M.tabs.table })).toBeChecked();
    expect(router.state.location.pathname).toBe("/app/matches");
    expect(router.state.location.search).toBe("?tab=table");
  });
});

describe("the match cards' states", () => {
  it.each([
    ["NOT_MAPPED", M.notMapped],
    ["NO_DATA", M.noData],
  ] as const)("show %s on both cards, without a last-updated line", async (state, text) => {
    await renderDashboard(emptyFixtures(state), emptyLeagueTable(state));

    expect(await within(nextMatchCard()).findByText(text)).toBeInTheDocument();
    expect(await within(tableCard()).findByText(text)).toBeInTheDocument();
    expect(screen.queryByText(/^עודכן לאחרונה/)).toBeNull();
    expect(scrollContainer()).toBeNull();
  });

  it("show each card's own error with a retry; the other card and the squad row still load", async () => {
    let fixturesCalls = 0;
    await renderDashboard(() =>
      fixturesCalls++ === 0 ? failure() : Response.json(fixturesBody()),
    );

    expect(
      await within(nextMatchCard()).findByText(he.dashboard.nextMatch.loadError),
    ).toBeInTheDocument();
    expect(await within(tableCard()).findByText("Team 1")).toBeInTheDocument();
    expect(
      await screen.findByRole("heading", { level: 2, name: he.dashboard.lines.title }),
    ).toBeInTheDocument();

    fireEvent.click(within(nextMatchCard()).getByRole("button", { name: M.retry }));

    expect(await within(nextMatchCard()).findByText("Hapoel Ashdod")).toBeInTheDocument();
    expect(fixturesCalls).toBe(2);
  });

  it("keep the next match when the squad summary and the table fail", async () => {
    await renderDashboard(fixturesBody(), failure, failure);

    expect(await screen.findByText(he.dashboard.loadError)).toBeInTheDocument();
    expect(
      await within(tableCard()).findByText(he.dashboard.leagueTable.loadError),
    ).toBeInTheDocument();
    expect(await within(nextMatchCard()).findByText("Hapoel Ashdod")).toBeInTheDocument();
  });

  it("show a loading message in each card until its answer", async () => {
    let release!: () => void;
    const gate = new Promise<void>((resolve) => (release = resolve));
    server.use(
      ...loggedIn(),
      summaryReturns(),
      fixturesReturns(fixturesBody(), { gate }),
      leagueTableReturns(leagueTableBody(), { gate }),
    );
    renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });

    expect(within(nextMatchCard()).getByRole("status")).toHaveTextContent(M.loading);
    expect(within(tableCard()).getByRole("status")).toHaveTextContent(M.loading);

    release();

    expect(await within(nextMatchCard()).findByText("Hapoel Ashdod")).toBeInTheDocument();
    expect(within(tableCard()).queryByRole("status")).toBeNull();
  });
});
