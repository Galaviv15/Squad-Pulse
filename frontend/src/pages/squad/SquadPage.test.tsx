import { act, fireEvent, screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from "vitest";
import { AGE_DEBOUNCE_MS } from "@/components/squad/AgeRangeFilter";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, PermissionLevel } from "@/lib/auth/currentUser";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { apiError, imageReturns, loginReturns, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import {
  playerBody,
  playerReturns,
  playersReturn,
  recordedPlayers,
  summaryReturns,
} from "@/test/msw/squad";
import { renderWithProviders } from "@/test/render";

const LIST = "/squad/players";
const photo = (id: string) => `/squad/players/${id}/photo`;

let fetchSpy: MockInstance<typeof fetch>;
let created: string[];

beforeEach(() => {
  created = [];
  fetchSpy = vi.spyOn(globalThis, "fetch");
  vi.spyOn(URL, "createObjectURL").mockImplementation(() => {
    const url = `blob:test/${created.length + 1}`;
    created.push(url);
    return url;
  });
  vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

/** The paths fetched so far (see AppShell.test.tsx: an unexpected image request fails silently). */
const fetchedPaths = () =>
  fetchSpy.mock.calls.map(([input]) => new URL(String(input), "http://localhost").pathname);
const listRequests = () => fetchedPaths().filter((path) => path === LIST).length;

interface RenderSquadOptions {
  players?: Player[];
  user?: Partial<CurrentUser>;
}

/**
 * Logged in at `path`, the list answering `players` and recording each request's query string
 * (as sent). Waits for the first list to be shown (rows, or the empty message).
 */
async function renderSquad(
  path = "/app/squad",
  { players = [playerBody()], user = {} }: RenderSquadOptions = {},
) {
  const list = recordedPlayers(players);
  server.use(refreshReturns("t1"), meReturns(user), list.handler);
  const rendered = renderWithProviders({ initialEntries: [path] });
  await screen.findByRole("heading", { level: 1, name: he.nav.squad });
  await waitFor(() => expect(screen.queryByText(he.squad.loading)).toBeNull());
  return { ...rendered, queries: list.queries };
}

const search = (router: { state: { location: { search: string } } }) =>
  router.state.location.search;

const statusRadio = (name: string) => screen.getByRole("radio", { name });
const combobox = (name: string) => screen.getByRole("combobox", { name });
const minAgeInput = () => screen.getByLabelText(he.squad.filters.minAge);
const maxAgeInput = () => screen.getByLabelText(he.squad.filters.maxAge);
const clearButton = () => screen.getByRole("button", { name: he.squad.filters.clear });

/** Opens a filter select and chooses an option as a mouse does (Base UI: pointerdown + click). */
async function choose(label: string, option: string) {
  fireEvent.click(combobox(label));
  const item = await screen.findByRole("option", { name: option });
  fireEvent.pointerDown(item, { pointerType: "mouse" });
  fireEvent.click(item);
  await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
}

/** Types an age and presses Enter. */
function enterAge(input: HTMLElement, value: string) {
  fireEvent.change(input, { target: { value } });
  fireEvent.keyDown(input, { key: "Enter" });
}

const bodyRows = () => screen.getAllByRole("row").slice(1);
const cellsOf = (row: HTMLElement) => within(row).getAllByRole("cell");
const rowOf = (name: string) => screen.getByRole("link", { name }).closest("tr")!;

describe("the list request", () => {
  it("is GET /squad/players without a query string by default", async () => {
    const { router, queries } = await renderSquad();

    expect(queries).toEqual([""]);
    expect(search(router)).toBe("");
    expect(statusRadio(he.squad.status.active)).toHaveAttribute("aria-checked", "true");
  });

  it.each([
    [
      "the released status",
      () => fireEvent.click(statusRadio(he.squad.status.released)),
      "status=released",
    ],
    ["the all status", () => fireEvent.click(statusRadio(he.squad.status.all)), "status=all"],
    ["a position", () => choose(he.squad.filters.position, "CB"), "position=CB"],
    [
      "a medical status",
      () => choose(he.squad.filters.medicalStatus, he.squad.medical.INJURED),
      "medicalStatus=INJURED",
    ],
    [
      "a foot",
      () => choose(he.squad.filters.preferredFoot, he.squad.foot.LEFT),
      "preferredFoot=LEFT",
    ],
    ["a minimum age", () => enterAge(minAgeInput(), "20"), "minAge=20"],
    ["a maximum age", () => enterAge(maxAgeInput(), "30"), "maxAge=30"],
  ])("carries %s, under the API's name, in the page URL too", async (_, change, expected) => {
    const { router, queries } = await renderSquad();

    await change();

    await waitFor(() => expect(queries).toEqual(["", expected]));
    expect(search(router)).toBe(`?${expected}`);
    expect(router.state.historyAction).toBe("REPLACE");
  });

  it("combines the filters in one request", async () => {
    const { router, queries } = await renderSquad();

    fireEvent.click(statusRadio(he.squad.status.all));
    await choose(he.squad.filters.position, "GK");
    enterAge(minAgeInput(), "20");
    enterAge(maxAgeInput(), "35");
    await choose(he.squad.filters.medicalStatus, he.squad.medical.FIT);
    await choose(he.squad.filters.preferredFoot, he.squad.foot.BOTH);

    const expected =
      "status=all&position=GK&minAge=20&maxAge=35&medicalStatus=FIT&preferredFoot=BOTH";
    await waitFor(() => expect(queries.at(-1)).toBe(expected));
    expect(search(router)).toBe(`?${expected}`);
  });

  it("removes a filter when its select goes back to הכל", async () => {
    const { router, queries } = await renderSquad("/app/squad?position=CB");

    await choose(he.squad.filters.position, he.squad.filters.any);

    await waitFor(() => expect(queries).toEqual(["position=CB", ""]));
    expect(search(router)).toBe("");
  });
});

describe("the filters in the URL", () => {
  it("are read into the controls and the request", async () => {
    const { queries } = await renderSquad("/app/squad?status=released&position=CB&minAge=20");

    expect(queries).toEqual(["status=released&position=CB&minAge=20"]);
    expect(statusRadio(he.squad.status.released)).toHaveAttribute("aria-checked", "true");
    expect(statusRadio(he.squad.status.active)).toHaveAttribute("aria-checked", "false");
    expect(combobox(he.squad.filters.position)).toHaveTextContent("CB");
    expect(minAgeInput()).toHaveValue("20");
    expect(maxAgeInput()).toHaveValue("");
  });

  it.each([
    ["?position=XX&minAge=10&foo=1&status=ACTIVE", "", ""],
    [
      "?status=released&foo=1&position=cb&maxAge=30",
      "status=released&maxAge=30",
      "?status=released&maxAge=30",
    ],
    ["?minAge=40&maxAge=30&medicalStatus=FIT", "medicalStatus=FIT", "?medicalStatus=FIT"],
    ["?status=active&position=GK", "position=GK", "?position=GK"],
    ["?maxAge=30&position=ST", "position=ST&maxAge=30", "?position=ST&maxAge=30"],
  ])(
    "are normalized from %s: a clean request, the URL rewritten in place",
    async (query, sent, url) => {
      const { router, queries } = await renderSquad(`/app/squad${query}`);

      expect(queries).toEqual([sent]);
      await waitFor(() => expect(search(router)).toBe(url));
      expect(router.state.historyAction).toBe("REPLACE");
      expect(screen.queryByText(he.squad.loadError)).toBeNull();
    },
  );

  it("are followed by the controls when the URL changes from outside", async () => {
    const { router, queries } = await renderSquad("/app/squad?minAge=20");

    await act(() => router.navigate("/app/squad?status=all&minAge=33&maxAge=40&position=LW"));

    expect(minAgeInput()).toHaveValue("33");
    expect(maxAgeInput()).toHaveValue("40");
    expect(combobox(he.squad.filters.position)).toHaveTextContent("LW");
    expect(statusRadio(he.squad.status.all)).toHaveAttribute("aria-checked", "true");
    await waitFor(() => expect(queries.at(-1)).toBe("status=all&position=LW&minAge=33&maxAge=40"));
  });

  it("are cleared, all but the status, by the clear button", async () => {
    const { router, queries } = await renderSquad(
      "/app/squad?status=all&position=GK&minAge=20&maxAge=30&medicalStatus=FIT&preferredFoot=LEFT",
    );
    expect(clearButton()).toBeEnabled();

    fireEvent.click(clearButton());

    await waitFor(() => expect(queries.at(-1)).toBe("status=all"));
    expect(search(router)).toBe("?status=all");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(minAgeInput()).toHaveValue("");
    expect(maxAgeInput()).toHaveValue("");
    expect(combobox(he.squad.filters.position)).toHaveTextContent(he.squad.filters.any);
    expect(statusRadio(he.squad.status.all)).toHaveAttribute("aria-checked", "true");
    expect(clearButton()).toBeDisabled();
  });

  it("can't be cleared while none is set (the status doesn't count)", async () => {
    await renderSquad("/app/squad?status=released");

    expect(clearButton()).toBeDisabled();
  });

  it("drop an invalid age draft on clear", async () => {
    const { router } = await renderSquad("/app/squad?position=GK");
    enterAge(minAgeInput(), "5");
    expect(minAgeInput()).toHaveAttribute("aria-invalid", "true");

    fireEvent.click(clearButton());

    await waitFor(() => expect(search(router)).toBe(""));
    expect(minAgeInput()).toHaveValue("");
    expect(minAgeInput()).not.toHaveAttribute("aria-invalid");
  });
});

describe("the status control", () => {
  it("is a radio group named סטטוס שחקנים, with the three statuses and one tab stop", async () => {
    await renderSquad();

    const group = screen.getByRole("radiogroup", { name: he.squad.statusLabel });
    const radios = within(group).getAllByRole("radio");
    expect(radios.map((radio) => radio.textContent)).toEqual([
      he.squad.status.active,
      he.squad.status.released,
      he.squad.status.all,
    ]);
    expect(radios.map((radio) => radio.getAttribute("tabindex"))).toEqual(["0", "-1", "-1"]);
    expect(screen.queryByRole("tablist")).toBeNull();
  });
});

describe("the age fields", () => {
  it("apply on blur", async () => {
    const { queries } = await renderSquad();

    fireEvent.change(maxAgeInput(), { target: { value: "30" } });
    fireEvent.blur(maxAgeInput());

    await waitFor(() => expect(queries).toEqual(["", "maxAge=30"]));
  });

  it(`apply after a ${AGE_DEBOUNCE_MS}ms pause in typing, not before`, async () => {
    const { queries } = await renderSquad();
    // Fake timers only from here on, for the debounce: the list has loaded with real ones, and
    // the request that follows is awaited with real ones again.
    vi.useFakeTimers();

    fireEvent.change(minAgeInput(), { target: { value: "2" } });
    act(() => vi.advanceTimersByTime(AGE_DEBOUNCE_MS - 100));
    fireEvent.change(minAgeInput(), { target: { value: "25" } });
    act(() => vi.advanceTimersByTime(AGE_DEBOUNCE_MS - 1));
    expect(listRequests()).toBe(1);
    expect(minAgeInput()).not.toHaveAttribute("aria-invalid");

    act(() => vi.advanceTimersByTime(1));
    vi.useRealTimers();

    await waitFor(() => expect(queries).toEqual(["", "minAge=25"]));
  });

  it.each([
    ["below 18", "17"],
    ["above 99", "100"],
    ["not a whole number", "20.5"],
    ["not a number", "abc"],
    ["negative", "-20"],
  ])("show an error, and send nothing, for an age %s", async (_, value) => {
    const { router } = await renderSquad();

    enterAge(minAgeInput(), value);

    const message = he.squad.filters.ageOutOfRange
      .replace("{{min}}", "18")
      .replace("{{max}}", "99");
    expect(screen.getByText(message)).toHaveClass("text-danger");
    expect(minAgeInput()).toHaveAttribute("aria-invalid", "true");
    expect(minAgeInput()).toHaveAccessibleDescription(message);
    expect(maxAgeInput()).not.toHaveAttribute("aria-invalid");
    fireEvent.blur(minAgeInput());
    expect(listRequests()).toBe(1);
    expect(search(router)).toBe("");
  });

  it("show the range message as 'between 18 and 99'", () => {
    expect(he.squad.filters.ageOutOfRange.replace("{{min}}", "18").replace("{{max}}", "99")).toBe(
      "גיל בין 18 ל־99",
    );
  });

  it("refuse a minimum above the maximum, keeping the last valid filters, until fixed", async () => {
    const { router, queries } = await renderSquad("/app/squad?minAge=30");

    enterAge(maxAgeInput(), "20");

    expect(screen.getByText(he.squad.filters.ageOrder)).toBeInTheDocument();
    expect(minAgeInput()).toHaveAttribute("aria-invalid", "true");
    expect(maxAgeInput()).toHaveAttribute("aria-invalid", "true");
    expect(maxAgeInput()).toHaveAccessibleDescription(he.squad.filters.ageOrder);
    expect(listRequests()).toBe(1);
    expect(search(router)).toBe("?minAge=30");

    enterAge(maxAgeInput(), "40");

    await waitFor(() => expect(queries).toEqual(["minAge=30", "minAge=30&maxAge=40"]));
    expect(screen.queryByText(he.squad.filters.ageOrder)).toBeNull();
    expect(maxAgeInput()).not.toHaveAttribute("aria-invalid");
  });

  it("remove a bound when emptied", async () => {
    const { router, queries } = await renderSquad("/app/squad?minAge=20&maxAge=30");

    enterAge(minAgeInput(), "");

    await waitFor(() => expect(queries).toEqual(["minAge=20&maxAge=30", "maxAge=30"]));
    expect(search(router)).toBe("?maxAge=30");
  });

  it("are numeric text fields, 18-99, with labels", async () => {
    await renderSquad();

    expect(minAgeInput()).toHaveAttribute("inputmode", "numeric");
    expect(maxAgeInput()).toHaveAttribute("inputmode", "numeric");
  });
});

describe("the table", () => {
  it("has the eight columns, in order, then the row actions", async () => {
    await renderSquad();

    const headers = screen.getAllByRole("columnheader");
    expect(headers.map((header) => header.textContent)).toEqual([
      "#",
      he.squad.columns.name,
      he.squad.columns.position,
      he.squad.columns.age,
      he.squad.columns.height,
      he.squad.columns.weight,
      he.squad.columns.foot,
      he.squad.columns.medicalStatus,
      he.squad.actions.column,
    ]);
    // The actions column's header is for screen readers only.
    expect(headers[8].querySelector(".sr-only")).toHaveTextContent(he.squad.actions.column);
    for (const header of headers) {
      expect(header).toHaveAttribute("scope", "col");
    }
  });

  it("keeps the server's order", async () => {
    // Neither by name, position nor number: only the server's order gives this one.
    const players = [
      playerBody({ id: "a", fullName: "Zeev", primaryPosition: "ST", jerseyNumber: 9 }),
      playerBody({ id: "b", fullName: "Avi", primaryPosition: "GK", jerseyNumber: 30 }),
      playerBody({ id: "c", fullName: "Moshe", primaryPosition: "CM", jerseyNumber: 1 }),
      playerBody({ id: "d", fullName: "Beni", primaryPosition: "CB", jerseyNumber: null }),
    ];
    await renderSquad("/app/squad", { players });

    expect(bodyRows().map((row) => within(row).getByRole("link").textContent)).toEqual([
      "Zeev",
      "Avi",
      "Moshe",
      "Beni",
    ]);
  });

  it("shows every field of a player", async () => {
    vi.useFakeTimers({ toFake: ["Date"], now: new Date(2026, 9, 6, 12) });
    const player = playerBody({
      id: "p7",
      fullName: "דני לוי",
      primaryPosition: "DM",
      secondaryPosition: "CB",
      jerseyNumber: 7,
      dateOfBirth: "2000-10-07",
      heightCm: 181,
      weightKg: 76,
      preferredFoot: "BOTH",
      medicalStatus: "FIT",
    });
    await renderSquad("/app/squad", { players: [player] });

    const cells = cellsOf(bodyRows()[0]);
    expect(cells.map((cell) => cell.textContent)).toEqual([
      "7",
      "דלדני לוי",
      "DMCB",
      "25",
      "181",
      "76",
      he.squad.foot.BOTH,
      he.squad.medical.FIT,
      "",
    ]);
    expect(within(cells[0]).getByText("7")).toHaveAttribute("dir", "ltr");
    expect(within(cells[1]).getByRole("link", { name: "דני לוי" })).toHaveAttribute(
      "href",
      "/app/squad/p7",
    );
    expect(within(cells[2]).getByText("DM")).toHaveAttribute("dir", "ltr");
    expect(within(cells[2]).getByText("CB")).toHaveAttribute("dir", "ltr");
    expect(within(cells[2]).getByText("CB")).toHaveAttribute("data-slot", "badge");
    expect(within(cells[7]).getByText(he.squad.medical.FIT)).toHaveClass("text-success");
  });

  it("shows ages by the browser's date: a year older on the birthday, not the day before", async () => {
    vi.useFakeTimers({ toFake: ["Date"], now: new Date(2026, 9, 6, 0, 5) });
    const players = [
      playerBody({ id: "a", fullName: "Today", dateOfBirth: "2000-10-06" }),
      playerBody({ id: "b", fullName: "Tomorrow", dateOfBirth: "2000-10-07" }),
    ];
    await renderSquad("/app/squad", { players });

    expect(cellsOf(rowOf("Today"))[3]).toHaveTextContent("26");
    expect(cellsOf(rowOf("Tomorrow"))[3]).toHaveTextContent("25");
  });

  it("shows — for every missing value", async () => {
    const player = playerBody({
      fullName: "No Data",
      primaryPosition: null,
      secondaryPosition: null,
      jerseyNumber: null,
      dateOfBirth: null,
      heightCm: null,
      weightKg: null,
      preferredFoot: null,
    });
    await renderSquad("/app/squad", { players: [player] });

    const cells = cellsOf(bodyRows()[0]);
    for (const index of [0, 2, 3, 4, 5, 6]) {
      expect(cells[index]).toHaveTextContent(/^—$/);
    }
  });

  it("shows no secondary chip when there's none", async () => {
    await renderSquad("/app/squad", { players: [playerBody({ primaryPosition: "GK" })] });

    const chips = within(cellsOf(bodyRows()[0])[2]).getAllByText(/^[A-Z]{2}$/);
    expect(chips.map((chip) => chip.textContent)).toEqual(["GK"]);
  });

  it("shows an injured player with the danger pill", async () => {
    await renderSquad("/app/squad", { players: [playerBody({ medicalStatus: "INJURED" })] });

    expect(within(cellsOf(bodyRows()[0])[7]).getByText(he.squad.medical.INJURED)).toHaveClass(
      "text-danger",
    );
  });

  it("dims a released player's row and marks it משוחרר", async () => {
    const players = [
      playerBody({ id: "a", fullName: "Active One" }),
      playerBody({ id: "b", fullName: "Gone Two", active: false }),
    ];
    await renderSquad("/app/squad?status=all", { players });

    const released = rowOf("Gone Two");
    expect(released).toHaveAttribute("data-released");
    expect(within(released).getByText(he.squad.released)).toBeInTheDocument();
    const active = rowOf("Active One");
    expect(active).not.toHaveAttribute("data-released");
    expect(within(active).queryByText(he.squad.released)).toBeNull();
  });
});

describe("player photos", () => {
  it("aren't requested for a player without one: initials instead", async () => {
    await renderSquad("/app/squad", { players: [playerBody({ id: "p1", hasPhoto: false })] });

    expect(fetchedPaths()).not.toContain(photo("p1"));
    expect(within(rowOf("Yossi Levi")).getByText("YL")).toHaveAttribute("aria-hidden", "true");
    expect(document.querySelectorAll("img")).toHaveLength(0);
  });

  it("are shown for a player with one", async () => {
    server.use(imageReturns(photo("p1")));
    await renderSquad("/app/squad", { players: [playerBody({ id: "p1", hasPhoto: true })] });

    const row = rowOf("Yossi Levi");
    await waitFor(() => expect(row.querySelector("img")).toHaveAttribute("src", "blob:test/1"));
    expect(row.querySelector("img")).toHaveAttribute("alt", "");
    expect(within(row).queryByText("YL")).toBeNull();
    expect(fetchedPaths().filter((path) => path === photo("p1"))).toHaveLength(1);
  });

  it("load independently: one held back doesn't hold the table or the others", async () => {
    const gate = deferred();
    server.use(imageReturns(photo("a"), gate.promise), imageReturns(photo("b")));
    const players = [
      playerBody({ id: "a", fullName: "Slow Photo", hasPhoto: true }),
      playerBody({ id: "b", fullName: "Fast Photo", hasPhoto: true }),
      playerBody({ id: "c", fullName: "No Photo", hasPhoto: false }),
    ];
    await renderSquad("/app/squad", { players });

    await waitFor(() => expect(rowOf("Fast Photo").querySelector("img")).not.toBeNull());
    expect(rowOf("Slow Photo").querySelector("img")).toBeNull();
    expect(within(rowOf("Slow Photo")).getByText("SP")).toBeInTheDocument();
    expect(bodyRows()).toHaveLength(3);

    gate.resolve();
    await waitFor(() => expect(rowOf("Slow Photo").querySelector("img")).not.toBeNull());
    expect(fetchedPaths()).not.toContain(photo("c"));
  });

  it.each([
    [404, "Not Found"],
    [500, "Internal Server Error"],
  ])("stay on initials, with no error shown, on a %i", async (status, error) => {
    const answered = { count: 0 };
    server.use(
      http.get(photo("p1"), () => {
        answered.count++;
        return apiError(status, error, "No photo");
      }),
    );
    await renderSquad("/app/squad", { players: [playerBody({ id: "p1", hasPhoto: true })] });

    await waitFor(() => expect(answered.count).toBe(1));
    expect(within(rowOf("Yossi Levi")).getByText("YL")).toBeInTheDocument();
    expect(document.querySelectorAll("img")).toHaveLength(0);
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText(he.squad.loadError)).toBeNull();
  });
});

describe("the list's states", () => {
  it("shows a loading state, and no count, until the first list arrives", async () => {
    const gate = deferred();
    server.use(
      refreshReturns("t1"),
      meReturns(),
      playersReturn([playerBody()], { gate: gate.promise }),
    );
    renderWithProviders({ initialEntries: ["/app/squad"] });

    expect(await screen.findByText(he.squad.loading)).toHaveAttribute("role", "status");
    expect(screen.queryByText(/מוצג/)).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();

    gate.resolve();
    expect(await screen.findByRole("table")).toBeInTheDocument();
    expect(screen.queryByText(he.squad.loading)).toBeNull();
  });

  it("shows an error with a retry when the list fails, and the retry can succeed", async () => {
    // Query retries are off in renderWithProviders, so the 500 fails the query at once.
    let fail = true;
    server.use(
      refreshReturns("t1"),
      meReturns(),
      http.get(LIST, () =>
        fail
          ? apiError(500, "Internal Server Error", "Unexpected error")
          : HttpResponse.json([playerBody()]),
      ),
    );
    renderWithProviders({ initialEntries: ["/app/squad"] });

    expect(await screen.findByText(he.squad.loadError)).toBeInTheDocument();
    expect(screen.queryByText(/מוצג/)).toBeNull();
    expect(screen.getByRole("radiogroup", { name: he.squad.statusLabel })).toBeInTheDocument();
    expect(combobox(he.squad.filters.position)).toBeEnabled();

    fail = false;
    fireEvent.click(screen.getByRole("button", { name: he.squad.retry }));

    expect(await screen.findByRole("link", { name: "Yossi Levi" })).toBeInTheDocument();
    expect(screen.queryByText(he.squad.loadError)).toBeNull();
  });

  it.each([
    ["", he.squad.empty.active],
    ["?status=released", he.squad.empty.released],
    ["?status=all", he.squad.empty.all],
  ])("says the squad is empty at /app/squad%s", async (query, text) => {
    await renderSquad(`/app/squad${query}`, { players: [] });

    expect(screen.getByText(text)).toBeInTheDocument();
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.queryByText(/מוצג/)).toBeNull();
    expect(screen.getAllByRole("button", { name: he.squad.filters.clear })).toHaveLength(1);
  });

  it("says nothing matches when a filter is set, with a clear button", async () => {
    const { router, queries } = await renderSquad("/app/squad?status=released&position=GK", {
      players: [],
    });

    expect(screen.getByText(he.squad.empty.filtered)).toBeInTheDocument();
    expect(screen.queryByText(he.squad.empty.released)).toBeNull();
    expect(screen.queryByText(/מוצג/)).toBeNull();
    const buttons = screen.getAllByRole("button", { name: he.squad.filters.clear });
    expect(buttons).toHaveLength(2);

    fireEvent.click(buttons[1]);

    await waitFor(() => expect(search(router)).toBe("?status=released"));
    await waitFor(() => expect(queries.at(-1)).toBe("status=released"));
  });

  it("keeps the previous rows, marked busy, while a new filter's list loads", async () => {
    const gate = deferred();
    const answered: string[] = [];
    server.use(
      refreshReturns("t1"),
      meReturns(),
      http.get(LIST, async ({ request }) => {
        const status = new URL(request.url).searchParams.get("status");
        answered.push(status ?? "");
        if (status === "released") {
          await gate.promise;
          return HttpResponse.json([
            playerBody({ id: "r", fullName: "Released One", active: false }),
          ]);
        }
        return HttpResponse.json([playerBody({ fullName: "Active One" })]);
      }),
    );
    renderWithProviders({ initialEntries: ["/app/squad"] });
    await screen.findByRole("link", { name: "Active One" });
    const frame = screen.getByRole("table").closest("[class*='rounded-lg']")!;
    expect(frame).not.toHaveAttribute("aria-busy");

    fireEvent.click(statusRadio(he.squad.status.released));

    await waitFor(() => expect(answered).toEqual(["", "released"]));
    expect(screen.getByRole("link", { name: "Active One" })).toBeInTheDocument();
    expect(frame).toHaveAttribute("aria-busy", "true");
    expect(screen.queryByText(he.squad.loading)).toBeNull();

    gate.resolve();
    expect(await screen.findByRole("link", { name: "Released One" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Active One" })).toBeNull();
    expect(frame).not.toHaveAttribute("aria-busy");
  });
});

describe("the count", () => {
  it("follows Hebrew's plural forms, as the runtime's rules give them", () => {
    const rules = new Intl.PluralRules("he");
    expect([0, 1, 2, 3, 11].map((n) => rules.select(n))).toEqual([
      "other",
      "one",
      "two",
      "other",
      "other",
    ]);
  });

  it.each([
    [1, "מוצג שחקן אחד"],
    [2, "מוצגים 2 שחקנים"],
    [3, "מוצגים 3 שחקנים"],
    [11, "מוצגים 11 שחקנים"],
  ])("reads %i players as %s", async (count, text) => {
    const players = Array.from({ length: count }, (_, i) =>
      playerBody({ id: `p${i}`, fullName: `Player ${i}` }),
    );
    await renderSquad("/app/squad", { players });

    const line = screen.getByText(text);
    expect(line).toHaveAttribute("aria-live", "polite");
    expect(line).toHaveClass("tabular-nums");
  });
});

describe("the add-player button", () => {
  it.each(["ADMIN", "EDIT_FULL"] as PermissionLevel[])(
    "is a link to the add page for %s",
    async (permissionLevel) => {
      const { router } = await renderSquad("/app/squad", { user: { permissionLevel } });

      const link = screen.getByRole("link", { name: he.squad.addPlayer });
      expect(link).toHaveAttribute("href", "/app/squad/new");

      fireEvent.click(link);

      expect(
        await screen.findByRole("heading", { level: 1, name: he.squad.addPlayer }),
      ).toBeInTheDocument();
      expect(router.state.location.pathname).toBe("/app/squad/new");
    },
  );

  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as PermissionLevel[])(
    "isn't there at all for %s",
    async (permissionLevel) => {
      await renderSquad("/app/squad", { user: { permissionLevel } });

      expect(screen.queryByRole("link", { name: he.squad.addPlayer })).toBeNull();
      expect(screen.queryByRole("button", { name: he.squad.addPlayer })).toBeNull();
    },
  );
});

describe("opening a player", () => {
  const squadNavLink = () =>
    within(screen.getByRole("navigation", { name: he.shell.navLabel })).getByRole("link", {
      name: he.nav.squad,
    });

  beforeEach(() => {
    server.use(playerReturns(playerBody()));
  });

  async function expectPlayerCard(router: { state: { location: { pathname: string } } }) {
    expect(
      await screen.findByRole("heading", { level: 1, name: he.squad.playerCard }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app/squad/p1");
    expect(
      await screen.findByRole("heading", { level: 2, name: "Yossi Levi" }),
    ).toBeInTheDocument();
    expect(squadNavLink()).toHaveAttribute("aria-current", "page");
  }

  it("works from the name link", async () => {
    const { router } = await renderSquad();

    fireEvent.click(screen.getByRole("link", { name: "Yossi Levi" }));

    await expectPlayerCard(router);
    expect(router.state.historyAction).toBe("PUSH");
  });

  it("works from anywhere else on the row", async () => {
    const { router } = await renderSquad();

    fireEvent.click(cellsOf(rowOf("Yossi Levi"))[3]);

    await expectPlayerCard(router);
  });

  it("navigates once when the name link itself is clicked", async () => {
    const { router } = await renderSquad();
    const navigations: string[] = [];
    router.subscribe((state) => navigations.push(state.location.pathname));

    fireEvent.click(screen.getByRole("link", { name: "Yossi Levi" }));

    await expectPlayerCard(router);
    expect(navigations.filter((path) => path === "/app/squad/p1")).toHaveLength(1);
  });

  it("doesn't happen when the click ends a text selection", async () => {
    const { router } = await renderSquad();
    vi.spyOn(window, "getSelection").mockReturnValue({
      toString: () => "Yossi",
    } as Selection);

    fireEvent.click(cellsOf(rowOf("Yossi Levi"))[4]);

    expect(router.state.location.pathname).toBe("/app/squad");
  });

  it("leads back to the squad from the player card", async () => {
    const { router } = await renderSquad();
    fireEvent.click(screen.getByRole("link", { name: "Yossi Levi" }));
    await expectPlayerCard(router);

    fireEvent.click(screen.getByRole("link", { name: he.squad.backToSquad }));

    expect(await screen.findByRole("link", { name: "Yossi Levi" })).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/app/squad");
  });
});

describe("after a logout", () => {
  it("fetches the list again for the next login: nothing is left in the cache", async () => {
    const { queries } = await renderSquad("/app/squad", {
      players: [playerBody({ fullName: "First Session" })],
    });
    expect(queries).toEqual([""]);
    server.use(http.post("/auth/logout", () => new HttpResponse(null, { status: 204 })));

    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));
    await screen.findByRole("heading", { name: he.auth.login.title });

    const gate = deferred();
    server.use(
      loginReturns("t2"),
      playersReturn([playerBody({ fullName: "Second Session" })], {
        gate: gate.promise,
        onRequest: (query) => queries.push(query.toString()),
      }),
      // The login lands on /app, whose dashboard requests the squad summary.
      summaryReturns(),
    );
    fireEvent.change(screen.getByLabelText(he.auth.fields.email), {
      target: { value: "coach@example.com" },
    });
    fireEvent.change(screen.getByLabelText(he.auth.fields.password), {
      target: { value: "secret-password" },
    });
    fireEvent.click(screen.getByRole("button", { name: he.auth.login.submit }));
    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });

    fireEvent.click(
      within(screen.getByRole("navigation", { name: he.shell.navLabel })).getByRole("link", {
        name: he.nav.squad,
      }),
    );

    expect(await screen.findByText(he.squad.loading)).toHaveAttribute("role", "status");
    expect(screen.queryByText("First Session")).toBeNull();
    expect(queries).toEqual(["", ""]);
    gate.resolve();
    expect(await screen.findByRole("link", { name: "Second Session" })).toBeInTheDocument();
  });
});
