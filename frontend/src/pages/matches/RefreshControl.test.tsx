import { act, fireEvent, screen } from "@testing-library/react";
import { HttpResponse } from "msw";
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import he from "@/i18n/locales/he.json";
import type { PermissionLevel } from "@/lib/auth/permissions";
import { FAILURE_REASONS, type RefreshStatus } from "@/lib/matches/types";
import { deferred } from "@/test/deferred";
import { watchLiveInsertion } from "@/test/liveRegion";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import {
  fixturesBody,
  fixturesReturns,
  leagueTableBody,
  leagueTableReturns,
  refreshStatusBody,
  refreshStatusReturns,
  refreshTriggerReturns,
} from "@/test/msw/matches";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

beforeAll(() => import("@/pages/matches/MatchesPage"));

// 12 Oct 2026, 18:00 in Israel. Only Date and the interval timers are faked (TanStack's
// refetchInterval and the countdown's tick): MSW and TanStack's own scheduling keep real
// timeouts.
const NOW = Date.parse("2026-10-12T15:00:00Z");
const at = (minutes: number) => new Date(NOW + minutes * 60_000).toISOString();

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date", "setInterval", "clearInterval"], now: NOW });
});

afterEach(() => {
  vi.useRealTimers();
});

const R = he.matches.refresh;

const running = (overrides: Partial<RefreshStatus> = {}) =>
  refreshStatusBody({ state: "RUNNING", startedAt: at(0), nextRefreshAt: at(10), ...overrides });
const started = () => running({ outcome: "STARTED" });
const succeeded = () =>
  refreshStatusBody({
    state: "SUCCEEDED",
    startedAt: at(0),
    finishedAt: at(0.1),
    nextRefreshAt: at(10),
  });
const failed = (failureReason: RefreshStatus["failureReason"] = "PARSE_ERROR") =>
  refreshStatusBody({
    state: "FAILED",
    failureReason,
    startedAt: at(0),
    finishedAt: "2026-10-12T15:00:06.329Z",
    nextRefreshAt: at(10),
  });

/**
 * Logged in at /app/matches as `level`, with both reads counted and the refresh status answering
 * `statuses` in turn. Waits for the page's data.
 */
async function renderMatches(
  level: PermissionLevel,
  statuses: (RefreshStatus | (() => Response))[] = [refreshStatusBody()],
) {
  const reads = { fixtures: 0, table: 0 };
  const status = refreshStatusReturns(...statuses);
  server.use(
    refreshReturns("t1"),
    meReturns({ permissionLevel: level }),
    fixturesReturns(fixturesBody(), { onRequest: () => reads.fixtures++ }),
    leagueTableReturns(leagueTableBody(), { onRequest: () => reads.table++ }),
    status.handler,
  );
  const rendered = renderWithProviders({ initialEntries: ["/app/matches"] });
  await screen.findByRole("heading", { level: 1, name: he.nav.matches });
  await screen.findByText("Hapoel Ashdod");
  return { ...rendered, reads, status };
}

const refreshButton = () => screen.findByRole("button", { name: R.button });

/** Lets fake intervals fire and the requests they send be answered and rendered. */
async function advance(ms: number) {
  await act(() => vi.advanceTimersByTimeAsync(ms));
  // MSW answers on real timers: give the answers and TanStack's notifications a moment.
  await act(() => new Promise((resolve) => setTimeout(resolve, 30)));
}

describe("who sees the refresh control", () => {
  it.each(["ADMIN", "EDIT_FULL"] as const)(
    "%s sees it, from GET /matches/refresh",
    async (level) => {
      const { status } = await renderMatches(level);

      expect(await refreshButton()).toBeEnabled();
      expect(status.requests.count).toBe(1);
    },
  );

  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as const)(
    "%s doesn't, and the status is never requested",
    async (level) => {
      // No GET /matches/refresh handler: a request would fail the test (the unmocked-request guard).
      server.use(
        refreshReturns("t1"),
        meReturns({ permissionLevel: level }),
        fixturesReturns(),
        leagueTableReturns(),
      );
      renderWithProviders({ initialEntries: ["/app/matches"] });
      await screen.findByText("Hapoel Ashdod");
      await advance(5_000);

      expect(screen.queryByRole("button", { name: R.button })).toBeNull();
      expect(screen.queryByRole("button", { name: /רענון|סורק/ })).toBeNull();
    },
  );

  it("is hidden while the club isn't mapped", async () => {
    await renderMatches("ADMIN", [refreshStatusBody({ state: "NOT_MAPPED" })]);
    await advance(0);

    expect(screen.queryByRole("button", { name: R.button })).toBeNull();
  });
});

describe("a refresh", () => {
  it("sends one POST, shows scanning, polls every 3 s while RUNNING, and refetches both reads when it succeeds", async () => {
    const { reads, status } = await renderMatches("EDIT_FULL", [
      refreshStatusBody(),
      running(),
      running(),
      succeeded(),
    ]);
    const trigger = refreshTriggerReturns(started());
    server.use(trigger.handler);
    expect(reads).toEqual({ fixtures: 1, table: 1 });

    fireEvent.click(await refreshButton());

    expect(await screen.findByRole("button", { name: R.running })).toBeDisabled();
    expect(trigger.requests.count).toBe(1);
    expect(status.requests.count).toBe(1);

    await advance(3_000);
    expect(status.requests.count).toBe(2);
    expect(screen.getByRole("button", { name: R.running })).toBeDisabled();

    await advance(3_000);
    expect(status.requests.count).toBe(3);

    await advance(3_000);
    expect(status.requests.count).toBe(4);
    expect(await screen.findByText(R.succeeded)).toBeInTheDocument();
    await advance(0);
    expect(reads).toEqual({ fixtures: 2, table: 2 });

    // Finished: no more polling.
    await advance(15_000);
    expect(status.requests.count).toBe(4);
    expect(reads).toEqual({ fixtures: 2, table: 2 });
    // Cooling down until nextRefreshAt (10 minutes after the start).
    expect(screen.getByRole("button", { name: /אפשר לרענן שוב בעוד/ })).toBeDisabled();
  });

  it("refetches both reads when the run fails too, and says why", async () => {
    const { reads } = await renderMatches("EDIT_FULL", [
      refreshStatusBody(),
      failed("PARSE_ERROR"),
    ]);
    server.use(refreshTriggerReturns(started()).handler);

    fireEvent.click(await refreshButton());
    await screen.findByRole("button", { name: R.running });
    await advance(3_000);

    expect(
      await screen.findByText(`הרענון האחרון נכשל (12.10.2026 18:00): ${R.failure.PARSE_ERROR}.`),
    ).toBeInTheDocument();
    expect(screen.getByText(R.failedData)).toBeInTheDocument();
    await advance(0);
    expect(reads).toEqual({ fixtures: 2, table: 2 });
  });

  it("announces scanning, then the failure, in a status region that was already there", async () => {
    await renderMatches("EDIT_FULL", [refreshStatusBody(), failed("SOURCE_BLOCKED")]);
    server.use(refreshTriggerReturns(started()).handler);
    await refreshButton();
    const scanning = watchLiveInsertion(R.scanning);
    const failure = watchLiveInsertion(R.failure.SOURCE_BLOCKED);

    fireEvent.click(await refreshButton());
    await screen.findByText(R.scanning);
    await advance(3_000);
    await screen.findByText(new RegExp(R.failure.SOURCE_BLOCKED));

    scanning.stop();
    failure.stop();
    expect(scanning.result.intoExistingRegion).toBe(true);
    expect(failure.result.intoExistingRegion).toBe(true);
  });

  it("sends one POST for a double click while it's in flight", async () => {
    await renderMatches("EDIT_FULL", [refreshStatusBody(), running()]);
    const gate = deferred();
    const trigger = refreshTriggerReturns(started(), gate.promise);
    server.use(trigger.handler);
    const button = await refreshButton();

    fireEvent.click(button);
    fireEvent.click(button);
    gate.resolve();

    expect(await screen.findByRole("button", { name: R.running })).toBeDisabled();
    expect(trigger.requests.count).toBe(1);
  });

  it("polls after ALREADY_RUNNING", async () => {
    const { status } = await renderMatches("EDIT_FULL", [refreshStatusBody(), running()]);
    server.use(refreshTriggerReturns(running({ outcome: "ALREADY_RUNNING" })).handler);

    fireEvent.click(await refreshButton());
    await screen.findByRole("button", { name: R.running });
    await advance(3_000);

    expect(status.requests.count).toBe(2);
  });

  it("starts polling when the screen opens on another user's running run", async () => {
    const { status, reads } = await renderMatches("ADMIN", [running(), running(), succeeded()]);

    expect(await screen.findByRole("button", { name: R.running })).toBeDisabled();
    await advance(3_000);
    expect(status.requests.count).toBe(2);
    await advance(3_000);
    expect(status.requests.count).toBe(3);
    await advance(0);
    expect(reads).toEqual({ fixtures: 2, table: 2 });
  });
});

describe("the cooldown", () => {
  it("disables the button with the minutes left, and enables it when they're over, without a request", async () => {
    const { status, reads } = await renderMatches("EDIT_FULL");
    const trigger = refreshTriggerReturns(
      refreshStatusBody({ outcome: "COOLDOWN", state: "SUCCEEDED", nextRefreshAt: at(4.5) }),
    );
    server.use(trigger.handler);

    fireEvent.click(await refreshButton());

    expect(
      await screen.findByRole("button", { name: "אפשר לרענן שוב בעוד 5 דקות" }),
    ).toBeDisabled();
    await advance(2 * 60_000);
    expect(screen.getByRole("button", { name: "אפשר לרענן שוב בעוד 3 דקות" })).toBeDisabled();
    await advance(60_000);
    expect(screen.getByRole("button", { name: "אפשר לרענן שוב בעוד שתי דקות" })).toBeDisabled();
    await advance(60_000);
    expect(screen.getByRole("button", { name: "אפשר לרענן שוב בעוד דקה" })).toBeDisabled();
    await advance(30_000);

    expect(await refreshButton()).toBeEnabled();
    expect(trigger.requests.count).toBe(1);
    expect(status.requests.count).toBe(1);
    expect(reads).toEqual({ fixtures: 1, table: 1 });
  });

  it("shows a cooldown from the status on load", async () => {
    await renderMatches("EDIT_FULL", [succeeded()]);

    expect(
      await screen.findByRole("button", { name: "אפשר לרענן שוב בעוד 10 דקות" }),
    ).toBeDisabled();
  });
});

describe("a refresh that doesn't start", () => {
  it("says the scraper is unavailable, keeps the button enabled and changes nothing", async () => {
    const { status, reads } = await renderMatches("EDIT_FULL", [failed("SOURCE_TIMEOUT")]);
    server.use(
      refreshTriggerReturns(refreshStatusBody({ outcome: "SCRAPER_UNAVAILABLE", state: "IDLE" }))
        .handler,
    );
    const unavailable = watchLiveInsertion(R.unavailable);
    await advance(10 * 60_000);

    fireEvent.click(await refreshButton());

    expect(await screen.findByText(R.unavailable)).toBeInTheDocument();
    unavailable.stop();
    expect(unavailable.result.intoExistingRegion).toBe(true);
    expect(await refreshButton()).toBeEnabled();
    await advance(3_000);
    expect(status.requests.count).toBe(1);
    expect(reads).toEqual({ fixtures: 1, table: 1 });
    expect(screen.getByText("Hapoel Ashdod")).toBeInTheDocument();
  });

  it.each([
    ["a 500", () => apiError(500, "Internal Server Error", "An unexpected error occurred")],
    ["no response", () => HttpResponse.error()],
  ])("shows a generic error for %s, the data unchanged", async (_, answer) => {
    const { reads } = await renderMatches("EDIT_FULL");
    server.use(refreshTriggerReturns(answer).handler);

    fireEvent.click(await refreshButton());

    expect(await screen.findByText(R.error)).toBeInTheDocument();
    expect(await refreshButton()).toBeEnabled();
    expect(reads).toEqual({ fixtures: 1, table: 1 });
    expect(screen.getByText("Hapoel Ashdod")).toBeInTheDocument();
  });

  it("hides the control when the POST says the club isn't mapped", async () => {
    await renderMatches("EDIT_FULL");
    server.use(
      refreshTriggerReturns(refreshStatusBody({ outcome: "NOT_MAPPED", state: "NOT_MAPPED" }))
        .handler,
    );

    fireEvent.click(await refreshButton());
    await advance(0);

    expect(screen.queryByRole("button", { name: /רענון|סורק/ })).toBeNull();
  });
});

describe("a failed run's reason", () => {
  it.each(FAILURE_REASONS)("%s is a plain sentence, never the enum", async (reason) => {
    await renderMatches("EDIT_FULL", [failed(reason)]);

    expect(
      await screen.findByText(`הרענון האחרון נכשל (12.10.2026 18:00): ${R.failure[reason]}.`),
    ).toBeInTheDocument();
    expect(document.body.textContent).not.toContain(reason);
    expect(document.body.textContent).not.toContain("FAILED");
  });

  it("is shown without a time when the run has no end time", async () => {
    await renderMatches("EDIT_FULL", [{ ...failed("NO_RESULT"), finishedAt: null }]);

    expect(
      await screen.findByText(`הרענון האחרון נכשל: ${R.failure.NO_RESULT}.`),
    ).toBeInTheDocument();
  });
});
