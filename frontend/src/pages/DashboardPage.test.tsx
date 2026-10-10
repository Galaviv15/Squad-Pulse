import { act, fireEvent, screen, within } from "@testing-library/react";
import { HttpResponse } from "msw";
import { beforeAll, describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { onPlayerWritten } from "@/lib/squad/players";
import { SQUAD_SUMMARY_QUERY_KEY } from "@/lib/squad/summary";
import type { SquadSummary } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { apiError, loggedIn } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { playerBody, playersReturn, summaryBody, summaryReturns } from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

// Load the lazy page's code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() => import("@/pages/squad/SquadPage"));

/** Logged in at /app with the summary answering `answer`; waits for the shell's <h1>. */
async function renderDashboard(answer: Parameters<typeof summaryReturns>[0] = summaryBody()) {
  const requests = { count: 0 };
  server.use(...loggedIn(), summaryReturns(answer, { onRequest: () => requests.count++ }));
  const rendered = renderWithProviders({ initialEntries: ["/app"] });
  await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
  return { ...rendered, requests };
}

/** Waits for the summary's cards, i.e. its data. */
const linesCard = async () =>
  cardOf(await screen.findByRole("heading", { level: 2, name: he.dashboard.lines.title }));

function cardOf(heading: HTMLElement) {
  const card = heading.closest<HTMLElement>("[data-slot=card]");
  expect(card).not.toBeNull();
  return card!;
}

const tile = (title: string) => cardOf(screen.getByRole("heading", { level: 2, name: title }));
const legendRows = (card: HTMLElement) =>
  within(card)
    .getAllByRole("listitem")
    .map((item) => item.textContent);
const ring = (card: HTMLElement) =>
  within(card).getByRole("img", { name: he.dashboard.lines.chartLabel });
const circles = (card: HTMLElement) => [...ring(card).querySelectorAll("circle")];
const placeholders = () => [...document.querySelectorAll<HTMLElement>("[data-slot=coming-soon]")];

const L = he.dashboard.lines;
const failure = () => apiError(500, "Internal Server Error", "An unexpected error occurred");

/** Answers each request with the next of `answers`, then the last one again. */
function inTurn(...answers: (SquadSummary | (() => Response))[]) {
  let next = 0;
  return () => {
    const answer = answers[Math.min(next++, answers.length - 1)];
    return typeof answer === "function" ? answer() : HttpResponse.json(answer);
  };
}

describe("the dashboard's squad cards", () => {
  it("show the active players, the average age and the lines, without percentages", async () => {
    await renderDashboard();
    const lines = await linesCard();

    expect(within(tile(he.dashboard.activePlayers)).getByText("23")).toHaveClass("tabular-nums");
    expect(within(tile(he.dashboard.averageAge)).getByText("26.4")).toBeInTheDocument();
    expect(
      within(tile(he.dashboard.averageAge)).getByText(he.dashboard.activePlayersOnly),
    ).toBeInTheDocument();
    // The center total, and the legend in line order with each count.
    expect(within(lines).getByText("23")).toBeInTheDocument();
    expect(within(lines).getByText(L.centerUnit)).toBeInTheDocument();
    expect(legendRows(lines)).toEqual([
      `${L.GOALKEEPERS}3`,
      `${L.DEFENSE}8`,
      `${L.MIDFIELD}7`,
      `${L.ATTACK}5`,
    ]);
    expect(document.body.textContent).not.toContain("%");
    // One <h1>, the shell's; the cards' titles are <h2>s.
    expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
  });

  it("draw one colored segment per line, in the legend's order, each with its title", async () => {
    await renderDashboard();
    const segments = circles(await linesCard());

    expect(segments.map((circle) => circle.getAttribute("class"))).toEqual([
      "stroke-chart-1",
      "stroke-chart-2",
      "stroke-chart-3",
      "stroke-chart-4",
    ]);
    expect(segments.map((circle) => circle.querySelector("title")?.textContent)).toEqual([
      `${L.GOALKEEPERS}: 3`,
      `${L.DEFENSE}: 8`,
      `${L.MIDFIELD}: 7`,
      `${L.ATTACK}: 5`,
    ]);
    for (const circle of segments) {
      expect(circle.getAttribute("r")).toBe("60");
      expect(circle.getAttribute("stroke-dasharray")).toMatch(/^\d+(\.\d+)? \d+(\.\d+)?$/);
      expect(circle.getAttribute("stroke-dashoffset")).toMatch(/^-?\d+(\.\d+)?$/);
    }
    // The first segment starts at the top: the ring is turned -90°.
    expect(segments[0].getAttribute("stroke-dashoffset")).toBe("0");
    expect(segments[0].parentElement).toHaveAttribute("transform", "rotate(-90 84 84)");
    // Not focusable: the legend carries the data.
    expect(ring(await linesCard()).querySelector("[tabindex]")).toBeNull();
  });

  it("show an average of 25 as 25.0", async () => {
    await renderDashboard(summaryBody({ averageAge: 25 }));
    await linesCard();

    expect(within(tile(he.dashboard.averageAge)).getByText("25.0")).toBeInTheDocument();
  });

  it("show an empty club as 0, a dash, an empty ring and four zeros, with no NaN", async () => {
    await renderDashboard(
      summaryBody({
        playerCount: 0,
        averageAge: null,
        lines: { GOALKEEPERS: 0, DEFENSE: 0, MIDFIELD: 0, ATTACK: 0 },
      }),
    );
    const lines = await linesCard();

    expect(within(tile(he.dashboard.activePlayers)).getByText("0")).toBeInTheDocument();
    expect(within(tile(he.dashboard.averageAge)).getByText("—")).toBeInTheDocument();
    expect(within(lines).getAllByText("0")).toHaveLength(5);
    expect(legendRows(lines)).toEqual([
      `${L.GOALKEEPERS}0`,
      `${L.DEFENSE}0`,
      `${L.MIDFIELD}0`,
      `${L.ATTACK}0`,
    ]);
    const ringCircles = circles(lines);
    expect(ringCircles).toHaveLength(1);
    expect(ringCircles[0]).toHaveAttribute("class", "stroke-muted");
    expect(ringCircles[0]).not.toHaveAttribute("stroke-dasharray");
    expect(document.body.innerHTML).not.toContain("NaN");
  });

  it("draw a single line as one full ring, without a dash", async () => {
    await renderDashboard(
      summaryBody({
        playerCount: 4,
        lines: { GOALKEEPERS: 0, DEFENSE: 0, MIDFIELD: 4, ATTACK: 0 },
      }),
    );
    const ringCircles = circles(await linesCard());

    expect(ringCircles).toHaveLength(1);
    expect(ringCircles[0]).toHaveAttribute("class", "stroke-chart-3");
    expect(ringCircles[0]).not.toHaveAttribute("stroke-dasharray");
  });

  it("show playerCount, in the tile and the center, when it's more than the lines add up to", async () => {
    // A player without a primary position is counted but in no line.
    await renderDashboard(summaryBody({ playerCount: 24 }));
    const lines = await linesCard();

    expect(within(tile(he.dashboard.activePlayers)).getByText("24")).toBeInTheDocument();
    expect(within(lines).getByText("24")).toBeInTheDocument();
  });

  it("link to the squad table", async () => {
    const { router } = await renderDashboard();
    await linesCard();
    server.use(playersReturn([playerBody()]));

    const link = screen.getByRole("link", { name: he.dashboard.toSquadTable });
    expect(link).toHaveAttribute("href", "/app/squad");
    fireEvent.click(link);

    expect(
      await screen.findByRole("heading", { level: 1, name: he.nav.squad }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app/squad");
    expect(await screen.findByText("Yossi Levi")).toBeInTheDocument();
  });

  it("refresh after a player write (the squad's queries are invalidated)", async () => {
    const { queryClient } = await renderDashboard(
      inTurn(
        summaryBody(),
        summaryBody({ playerCount: 22, lines: { ...summaryBody().lines, ATTACK: 4 } }),
      ),
    );
    await linesCard();
    expect(within(tile(he.dashboard.activePlayers)).getByText("23")).toBeInTheDocument();

    act(() => onPlayerWritten(queryClient, playerBody({ active: false })));

    expect(await within(tile(he.dashboard.activePlayers)).findByText("22")).toBeInTheDocument();
    expect(legendRows(await linesCard())[3]).toBe(`${L.ATTACK}4`);
  });
});

describe("the dashboard's states", () => {
  it("shows a loading message in place of the squad cards, and the placeholders already", async () => {
    const gate = deferred();
    server.use(...loggedIn(), summaryReturns(summaryBody(), { gate: gate.promise }));
    renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
    const main = screen.getByRole("main");

    expect(within(main).getByRole("status")).toHaveTextContent(he.auth.loading);
    expect(placeholders()).toHaveLength(4);
    // No numbers and no ring before the data: never a 0 that isn't one.
    expect(within(main).queryByRole("heading", { name: he.dashboard.activePlayers })).toBeNull();
    expect(within(main).queryByRole("img")).toBeNull();
    expect(within(main).queryByText("0")).toBeNull();

    gate.resolve();

    await linesCard();
    expect(within(main).queryByRole("status")).toBeNull();
  });

  it("shows the error with a retry in place of the squad cards; a retry loads them", async () => {
    const { requests } = await renderDashboard(inTurn(failure, summaryBody()));

    const main = screen.getByRole("main");
    expect(await within(main).findByText(he.dashboard.loadError)).toBeInTheDocument();
    expect(within(main).queryByRole("heading", { name: he.dashboard.lines.title })).toBeNull();
    expect(placeholders()).toHaveLength(4);

    fireEvent.click(within(main).getByRole("button", { name: he.squad.retry }));

    await linesCard();
    expect(screen.queryByText(he.dashboard.loadError)).toBeNull();
    expect(requests.count).toBe(2);
  });

  it("keeps the data when a background refetch fails", async () => {
    const { queryClient, requests } = await renderDashboard(inTurn(summaryBody(), failure));
    await linesCard();

    await act(() => queryClient.refetchQueries({ queryKey: SQUAD_SUMMARY_QUERY_KEY }));
    // TanStack Query tells its observers in a later tick: let the page render the failure.
    await act(() => new Promise((resolve) => setTimeout(resolve, 20)));

    expect(requests.count).toBe(2);
    expect(queryClient.getQueryState(SQUAD_SUMMARY_QUERY_KEY)?.status).toBe("error");
    expect(within(tile(he.dashboard.activePlayers)).getByText("23")).toBeInTheDocument();
    expect(screen.queryByText(he.dashboard.loadError)).toBeNull();
    expect(screen.queryByRole("button", { name: he.squad.retry })).toBeNull();
  });
});

describe("the dashboard's placeholders", () => {
  it("show the next training, next match, league table and weekly schedule as coming soon", async () => {
    await renderDashboard();

    expect(placeholders().map((card) => within(card).getByRole("heading").textContent)).toEqual([
      he.dashboard.placeholders.training.title,
      he.dashboard.placeholders.match.title,
      he.dashboard.placeholders.league.title,
      he.dashboard.weekly.title,
    ]);
    for (const [card, body] of [
      [placeholders()[0], he.dashboard.placeholders.training.body],
      [placeholders()[1], he.dashboard.placeholders.match.body],
      [placeholders()[2], he.dashboard.placeholders.league.body],
      [placeholders()[3], he.dashboard.weekly.caption],
    ] as const) {
      expect(within(card).getByText(body)).toBeInTheDocument();
      expect(within(card).getByText(he.dashboard.comingSoon)).toBeInTheDocument();
    }
  });

  it("contain no links, buttons or anything else focusable", async () => {
    await renderDashboard();

    for (const card of placeholders()) {
      expect(within(card).queryAllByRole("link")).toEqual([]);
      expect(within(card).queryAllByRole("button")).toEqual([]);
      expect(card.querySelectorAll("a, button, input, select, textarea, [tabindex]")).toHaveLength(
        0,
      );
    }
  });

  it("list the week's seven days in order, Sunday first", async () => {
    await renderDashboard();
    const weekly = placeholders()[3];

    expect(
      within(weekly)
        .getAllByRole("listitem")
        .map((day) => day.textContent),
    ).toEqual([
      he.dashboard.weekly.days.sunday,
      he.dashboard.weekly.days.monday,
      he.dashboard.weekly.days.tuesday,
      he.dashboard.weekly.days.wednesday,
      he.dashboard.weekly.days.thursday,
      he.dashboard.weekly.days.friday,
      he.dashboard.weekly.days.saturday,
    ]);
    expect(he.dashboard.weekly.days.sunday).toBe("ראשון");
    expect(he.dashboard.weekly.days.saturday).toBe("שבת");
  });
});
