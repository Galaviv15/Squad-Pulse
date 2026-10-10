import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import { HttpResponse } from "msw";
import {
  afterEach,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
  vi,
  type MockInstance,
} from "vitest";
import he from "@/i18n/locales/he.json";
import type { CurrentUser, PermissionLevel } from "@/lib/auth/currentUser";
import { toIsoDate } from "@/lib/squad/dates";
import type { Player } from "@/lib/squad/types";
import { deferred } from "@/test/deferred";
import { apiError, meReturns, refreshReturns } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import {
  conflict,
  createPlayerReturns,
  playerBody,
  playerReturns,
  playersReturn,
} from "@/test/msw/squad";
import { choose, combobox, errorOf, fields, input, submitButton, type } from "@/test/playerForm";
import { renderWithProviders } from "@/test/render";

// Load the lazy pages' code up front: the first lazy load must not count against findBy's 1 s wait.
beforeAll(() =>
  Promise.all([
    import("@/pages/squad/NewPlayerPage"),
    import("@/pages/squad/PlayerCardPage"),
    import("@/pages/squad/SquadPage"),
  ]),
);

let fetchSpy: MockInstance<typeof fetch>;

beforeEach(() => {
  fetchSpy = vi.spyOn(globalThis, "fetch");
});

afterEach(() => {
  vi.restoreAllMocks();
});

const fetchedPaths = () =>
  fetchSpy.mock.calls.map(([input]) => new URL(String(input), "http://localhost").pathname);

const submit = () => fireEvent.click(submitButton(he.squad.form.submitCreate));

/** Logged in at the add form, after the squad (so Back has somewhere to go). */
async function renderNew(user: Partial<CurrentUser> = {}) {
  server.use(refreshReturns("t1"), meReturns(user), playersReturn([]));
  const rendered = renderWithProviders({ initialEntries: ["/app/squad", "/app/squad/new"] });
  await screen.findByRole("heading", { level: 1, name: he.squad.addPlayer });
  return rendered;
}

/** Fills a valid form: name, CB, born 20.05.1998. */
async function fillRequired() {
  await screen.findByRole("button", { name: he.squad.form.submitCreate });
  type(fields.fullName, "Avi Cohen");
  await choose(fields.primaryPosition, "CB");
  type(fields.dateOfBirth, "1998-05-20");
}

describe("the add-player form", () => {
  it("has every field, labelled, with the required ones marked", async () => {
    await renderNew();
    await screen.findByRole("button", { name: he.squad.form.submitCreate });

    for (const label of [fields.fullName, fields.dateOfBirth]) {
      expect(input(label)).toHaveAttribute("aria-required", "true");
    }
    // The "*" is visual only: not part of the accessible name.
    expect(screen.getByRole("textbox", { name: fields.fullName })).toBe(input(fields.fullName));
    for (const label of [fields.primaryPosition, fields.medicalStatus]) {
      expect(combobox(label)).toHaveAttribute("aria-required", "true");
    }
    for (const label of [fields.jerseyNumber, fields.heightCm, fields.weightKg]) {
      expect(input(label)).not.toHaveAttribute("aria-required");
      expect(input(label)).toHaveAttribute("inputmode", "numeric");
      expect(input(label)).toHaveAttribute("dir", "ltr");
      expect(input(label)).toHaveClass("tabular-nums");
    }
    expect(combobox(fields.secondaryPosition)).not.toHaveAttribute("aria-required");
    expect(combobox(fields.preferredFoot)).not.toHaveAttribute("aria-required");
    expect(input(fields.dateOfBirth)).toHaveAttribute("type", "date");
    expect(input(fields.dateOfBirth)).toHaveAttribute("max", toIsoDate(new Date()));
    expect(combobox(fields.medicalStatus)).toHaveTextContent(he.squad.medical.FIT);
    expect(screen.getByRole("link", { name: he.squad.form.cancel })).toHaveAttribute(
      "href",
      "/app/squad",
    );
  });

  it("lists the positions in enum order, as LTR codes, with ללא first for the secondary", async () => {
    await renderNew();
    await screen.findByRole("button", { name: he.squad.form.submitCreate });

    fireEvent.click(combobox(fields.secondaryPosition));
    const options = await screen.findAllByRole("option");

    expect(options.map((option) => option.textContent)).toEqual([
      he.squad.form.none,
      ...["GK", "CB", "RB", "LB", "DM", "CM", "AM", "RW", "LW", "ST"],
    ]);
    expect(within(options[1]).getByText("GK")).toHaveAttribute("dir", "ltr");
  });

  it("sends exactly the form's values and replaces itself with the new player's card", async () => {
    const created = createPlayerReturns();
    server.use(
      created.handler,
      playerReturns(() => playerBody({ ...created.bodies[0], id: "new1" } as Partial<Player>)),
    );
    const { router } = await renderNew();
    await fillRequired();
    await choose(fields.secondaryPosition, "RB");
    type(fields.jerseyNumber, "10");
    type(fields.heightCm, "180");
    type(fields.weightKg, "75");
    await choose(fields.preferredFoot, he.squad.foot.LEFT);

    submit();

    await waitFor(() => expect(router.state.location.pathname).toBe("/app/squad/new1"));
    expect(created.bodies).toEqual([
      {
        fullName: "Avi Cohen",
        primaryPosition: "CB",
        secondaryPosition: "RB",
        jerseyNumber: 10,
        dateOfBirth: "1998-05-20",
        heightCm: 180,
        weightKg: 75,
        preferredFoot: "LEFT",
        medicalStatus: "FIT",
      },
    ]);
    expect(router.state.historyAction).toBe("REPLACE");
    expect(await screen.findByRole("heading", { level: 2, name: "Avi Cohen" })).toBeInTheDocument();

    // Back skips the form: it was replaced.
    await router.navigate(-1);
    expect(router.state.location.pathname).toBe("/app/squad");
  });

  it("sends the empty optional fields as null", async () => {
    const created = createPlayerReturns();
    server.use(created.handler, playerReturns(playerBody({ id: "new1" })));
    await renderNew();
    await fillRequired();

    submit();

    await waitFor(() => expect(created.bodies).toHaveLength(1));
    expect(created.bodies[0]).toEqual({
      fullName: "Avi Cohen",
      primaryPosition: "CB",
      secondaryPosition: null,
      jerseyNumber: null,
      dateOfBirth: "1998-05-20",
      heightCm: null,
      weightKg: null,
      preferredFoot: null,
      medicalStatus: "FIT",
    });
  });

  it("sends nothing for an invalid form, shows each error and focuses the first", async () => {
    await renderNew();
    await screen.findByRole("button", { name: he.squad.form.submitCreate });
    type(fields.jerseyNumber, "100");
    type(fields.weightKg, "39");
    const requestsBefore = fetchSpy.mock.calls.length;

    submit();

    expect(input(fields.fullName)).toHaveFocus();
    expect(input(fields.fullName)).toHaveAttribute("aria-invalid", "true");
    expect(errorOf(input(fields.fullName))).toBe(he.squad.form.errors.required);
    expect(errorOf(combobox(fields.primaryPosition))).toBe(he.squad.form.errors.required);
    expect(errorOf(input(fields.dateOfBirth))).toBe(he.squad.form.errors.required);
    expect(errorOf(input(fields.jerseyNumber))).toBe(he.squad.form.errors.jerseyNumberRange);
    expect(errorOf(input(fields.weightKg))).toBe(he.squad.form.errors.weightRange);
    expect(errorOf(input(fields.heightCm))).toBeNull();
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(fetchSpy.mock.calls.length).toBe(requestsBefore);
  });

  it("validates as the user types after the first submit", async () => {
    await renderNew();
    await screen.findByRole("button", { name: he.squad.form.submitCreate });
    type(fields.fullName, "Avi");
    expect(errorOf(input(fields.fullName))).toBeNull();
    type(fields.fullName, "");
    expect(errorOf(input(fields.fullName))).toBeNull();

    submit();
    expect(errorOf(input(fields.fullName))).toBe(he.squad.form.errors.required);
    type(fields.fullName, "Avi");
    expect(errorOf(input(fields.fullName))).toBeNull();
    type(fields.dateOfBirth, "2026-01-01");
    expect(errorOf(input(fields.dateOfBirth))).toBe(he.squad.form.errors.ageRange);
  });

  it("shows the secondary position's error as soon as it equals the primary", async () => {
    await renderNew();
    await screen.findByRole("button", { name: he.squad.form.submitCreate });
    await choose(fields.secondaryPosition, "ST");
    expect(errorOf(combobox(fields.secondaryPosition))).toBeNull();

    await choose(fields.primaryPosition, "ST");

    expect(errorOf(combobox(fields.secondaryPosition))).toBe(
      he.squad.form.errors.secondaryEqualsPrimary,
    );
    expect(combobox(fields.secondaryPosition)).toHaveTextContent("ST");
  });

  it("puts a 400's field errors on their fields, in a generic text", async () => {
    const created = createPlayerReturns(() =>
      apiError(400, "Validation Failed", "Request validation failed", [
        "dateOfBirth: age must be between 18 and 99",
        "heightCm: must be less than or equal to 220",
      ]),
    );
    server.use(created.handler);
    await renderNew();
    await fillRequired();

    submit();

    await waitFor(() =>
      expect(errorOf(input(fields.dateOfBirth))).toBe(he.squad.form.errors.invalidValue),
    );
    expect(errorOf(input(fields.heightCm))).toBe(he.squad.form.errors.invalidValue);
    expect(input(fields.dateOfBirth)).toHaveFocus();
    expect(screen.queryByText(/must be/)).toBeNull();
    expect(screen.queryByRole("alert")).toBeNull();
    expect(input(fields.fullName)).toHaveValue("Avi Cohen");

    // Changing the field clears its server error.
    type(fields.dateOfBirth, "1998-05-21");
    expect(errorOf(input(fields.dateOfBirth))).toBeNull();
  });

  it("shows the generic alert for a 400 it can't place on a field", async () => {
    server.use(
      createPlayerReturns(() =>
        apiError(400, "Validation Failed", "Request validation failed", [
          "version: must not be null",
        ]),
      ).handler,
    );
    await renderNew();
    await fillRequired();

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(he.squad.form.saveInvalid);
  });

  it("puts a taken jersey number on its field and focuses it", async () => {
    server.use(createPlayerReturns(() => conflict("JERSEY_NUMBER_TAKEN", "Jersey taken")).handler);
    await renderNew();
    await fillRequired();
    type(fields.jerseyNumber, "7");

    submit();

    await waitFor(() =>
      expect(errorOf(input(fields.jerseyNumber))).toBe(he.squad.form.errors.jerseyNumberTaken),
    );
    expect(input(fields.jerseyNumber)).toHaveFocus();
    expect(input(fields.jerseyNumber)).toHaveValue("7");
    expect(screen.queryByText("Jersey taken")).toBeNull();
  });

  it.each([
    ["an unknown 409 code", () => conflict("SOMETHING_NEW"), he.squad.form.saveInvalid],
    ["a 403", () => apiError(403, "Forbidden", "Access denied"), he.squad.noPermission],
    [
      "a 500",
      () => apiError(500, "Internal Server Error", "An unexpected error occurred"),
      he.squad.form.saveFailed,
    ],
    ["no response", () => HttpResponse.error(), he.squad.form.saveFailed],
  ])("shows an alert for %s and keeps the input", async (_name, answer, text) => {
    server.use(createPlayerReturns(answer).handler);
    await renderNew();
    await fillRequired();

    submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(text);
    expect(input(fields.fullName)).toHaveValue("Avi Cohen");
    expect(combobox(fields.primaryPosition)).toHaveTextContent("CB");
    expect(submitButton(he.squad.form.submitCreate)).toBeEnabled();
  });

  it("sends one request for a double submit, showing that it's saving", async () => {
    const gate = deferred();
    const created = createPlayerReturns(async (body) => {
      await gate.promise;
      return HttpResponse.json(playerBody({ ...body, id: "new1" } as Partial<Player>), {
        status: 201,
      });
    });
    server.use(created.handler, playerReturns(playerBody({ id: "new1" })));
    const { router } = await renderNew();
    await fillRequired();

    const button = submitButton(he.squad.form.submitCreate);
    fireEvent.click(button);
    fireEvent.click(button);
    fireEvent.submit(button.closest("form")!);

    await waitFor(() => expect(button).toHaveTextContent(he.squad.form.saving));
    expect(button).toBeDisabled();
    gate.resolve();
    await waitFor(() => expect(router.state.location.pathname).toBe("/app/squad/new1"));
    expect(created.bodies).toHaveLength(1);
  });
});

describe("the add-player page's guard", () => {
  it.each(["EDIT_PARTIAL", "VIEW_ONLY"] as PermissionLevel[])(
    "shows %s the no-permission message, with no form and no request",
    async (permissionLevel) => {
      server.use(refreshReturns("t1"), meReturns({ permissionLevel }));
      renderWithProviders({ initialEntries: ["/app/squad/new"] });

      expect(await screen.findByText(he.squad.noPermission)).toBeInTheDocument();
      expect(screen.getByRole("heading", { level: 1 })).toHaveTextContent(he.squad.addPlayer);
      expect(screen.getByRole("link", { name: he.squad.backToSquad })).toHaveAttribute(
        "href",
        "/app/squad",
      );
      expect(screen.queryByRole("button", { name: he.squad.form.submitCreate })).toBeNull();
      await new Promise((resolve) => setTimeout(resolve, 20));
      expect(fetchedPaths()).toEqual(["/auth/refresh", "/auth/users/me"]);
    },
  );
});
