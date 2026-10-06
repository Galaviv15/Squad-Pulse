import { describe, expect, it } from "vitest";
import { playerBody } from "@/test/msw/squad";
import {
  EMPTY_PLAYER_FORM,
  formValuesToCreateBody,
  formValuesToUpdateBody,
  jerseyNumberError,
  playerToFormValues,
  validatePlayerForm,
  type PlayerFormValues,
} from "./form";

/** 20 May 2026, noon, local time. */
const TODAY = new Date(2026, 4, 20, 12);

const valid: PlayerFormValues = {
  fullName: "Yossi Levi",
  primaryPosition: "CB",
  secondaryPosition: "",
  jerseyNumber: "",
  dateOfBirth: "1998-05-20",
  heightCm: "",
  weightKg: "",
  preferredFoot: "",
  medicalStatus: "FIT",
};

const errorsOf = (patch: Partial<PlayerFormValues>, mode: "create" | "edit" = "create") =>
  validatePlayerForm({ ...valid, ...patch }, TODAY, mode);

describe("validatePlayerForm", () => {
  it("accepts a minimal valid form", () => {
    expect(errorsOf({})).toEqual({});
  });

  it("flags every required field of an empty form", () => {
    expect(validatePlayerForm({ ...EMPTY_PLAYER_FORM, medicalStatus: "" }, TODAY, "edit")).toEqual({
      fullName: "squad.form.errors.required",
      primaryPosition: "squad.form.errors.required",
      dateOfBirth: "squad.form.errors.required",
      medicalStatus: "squad.form.errors.required",
    });
  });

  describe("name", () => {
    it.each(["", " ", "   \t "])("is required: %o", (fullName) => {
      expect(errorsOf({ fullName })).toEqual({ fullName: "squad.form.errors.required" });
    });

    it("accepts 100 characters and refuses 101", () => {
      expect(errorsOf({ fullName: "a".repeat(100) })).toEqual({});
      expect(errorsOf({ fullName: "a".repeat(101) })).toEqual({
        fullName: "squad.form.errors.nameTooLong",
      });
    });

    it("counts the length after trimming, as the server does", () => {
      expect(errorsOf({ fullName: `  ${"a".repeat(100)}  ` })).toEqual({});
    });

    it("counts UTF-16 units, like @Size", () => {
      // "𝔸" is outside the BMP: two UTF-16 units each.
      expect(errorsOf({ fullName: "𝔸".repeat(50) })).toEqual({});
      expect(errorsOf({ fullName: `${"𝔸".repeat(50)}a` })).toEqual({
        fullName: "squad.form.errors.nameTooLong",
      });
    });
  });

  describe("positions", () => {
    it("needs a primary position", () => {
      expect(errorsOf({ primaryPosition: "" })).toEqual({
        primaryPosition: "squad.form.errors.required",
      });
    });

    it("refuses a secondary position equal to the primary", () => {
      expect(errorsOf({ primaryPosition: "ST", secondaryPosition: "ST" })).toEqual({
        secondaryPosition: "squad.form.errors.secondaryEqualsPrimary",
      });
    });

    it("accepts a different secondary position, or none", () => {
      expect(errorsOf({ secondaryPosition: "DM" })).toEqual({});
      expect(errorsOf({ secondaryPosition: "" })).toEqual({});
    });
  });

  describe.each([
    ["jerseyNumber", "squad.form.errors.jerseyNumberRange", ["1", "99", " 7 ", "07"], ["0", "100"]],
    ["heightCm", "squad.form.errors.heightRange", ["140", "220"], ["139", "221"]],
    ["weightKg", "squad.form.errors.weightRange", ["40", "150"], ["39", "151"]],
  ] as const)("%s", (field, key, accepted, refused) => {
    it("is optional", () => {
      expect(errorsOf({ [field]: "" })).toEqual({});
      expect(errorsOf({ [field]: "  " })).toEqual({});
    });

    it.each(accepted)("accepts %o", (value) => {
      expect(errorsOf({ [field]: value })).toEqual({});
    });

    it.each([...refused, "1.5", "-5", "+5", "abc", "1e2", "1000", "٧"])("refuses %o", (value) => {
      expect(errorsOf({ [field]: value })).toEqual({ [field]: key });
    });
  });

  describe("date of birth", () => {
    it("accepts the 18th birthday today, and refuses the day before it", () => {
      expect(errorsOf({ dateOfBirth: "2008-05-20" })).toEqual({});
      expect(errorsOf({ dateOfBirth: "2008-05-21" })).toEqual({
        dateOfBirth: "squad.form.errors.ageRange",
      });
    });

    it("accepts the day before the 100th birthday, and refuses the birthday itself", () => {
      expect(errorsOf({ dateOfBirth: "1926-05-21" })).toEqual({});
      expect(errorsOf({ dateOfBirth: "1926-05-20" })).toEqual({
        dateOfBirth: "squad.form.errors.ageRange",
      });
    });

    it("refuses a date in the future", () => {
      expect(errorsOf({ dateOfBirth: "2026-05-21" })).toEqual({
        dateOfBirth: "squad.form.errors.invalidDate",
      });
    });

    it("refuses today as too young, not as a future date", () => {
      expect(errorsOf({ dateOfBirth: "2026-05-20" })).toEqual({
        dateOfBirth: "squad.form.errors.ageRange",
      });
    });

    it.each(["2001-02-29", "1998-13-01", "1998-00-10", "1998-05-32", "20.05.1998", "1998-5-20"])(
      "refuses the invalid date %o",
      (dateOfBirth) => {
        expect(errorsOf({ dateOfBirth })).toEqual({ dateOfBirth: "squad.form.errors.invalidDate" });
      },
    );

    it("accepts 29 February of a leap year", () => {
      expect(errorsOf({ dateOfBirth: "2000-02-29" })).toEqual({});
    });
  });

  it("needs a medical status on edit only", () => {
    expect(errorsOf({ medicalStatus: "" }, "create")).toEqual({});
    expect(errorsOf({ medicalStatus: "" }, "edit")).toEqual({
      medicalStatus: "squad.form.errors.required",
    });
  });
});

describe("playerToFormValues", () => {
  it("turns every null into an empty field", () => {
    expect(
      playerToFormValues(
        playerBody({
          primaryPosition: null,
          secondaryPosition: null,
          jerseyNumber: null,
          dateOfBirth: null,
          heightCm: null,
          weightKg: null,
          preferredFoot: null,
        }),
      ),
    ).toEqual({
      fullName: "Yossi Levi",
      primaryPosition: "",
      secondaryPosition: "",
      jerseyNumber: "",
      dateOfBirth: "",
      heightCm: "",
      weightKg: "",
      preferredFoot: "",
      medicalStatus: "FIT",
    });
  });

  it("writes the numbers as text", () => {
    expect(
      playerToFormValues(
        playerBody({ secondaryPosition: "RB", preferredFoot: "LEFT", medicalStatus: "INJURED" }),
      ),
    ).toEqual({
      fullName: "Yossi Levi",
      primaryPosition: "CB",
      secondaryPosition: "RB",
      jerseyNumber: "4",
      dateOfBirth: "1998-05-20",
      heightCm: "182",
      weightKg: "78",
      preferredFoot: "LEFT",
      medicalStatus: "INJURED",
    });
  });
});

describe("form → API bodies", () => {
  const full: PlayerFormValues = {
    fullName: "  Avi Cohen ",
    primaryPosition: "ST",
    secondaryPosition: "LW",
    jerseyNumber: " 09",
    dateOfBirth: "2000-01-31",
    heightCm: "180",
    weightKg: "75",
    preferredFoot: "BOTH",
    medicalStatus: "INJURED",
  };

  it("sends numbers as numbers, and the name as typed", () => {
    expect(formValuesToCreateBody(full)).toEqual({
      fullName: "  Avi Cohen ",
      primaryPosition: "ST",
      secondaryPosition: "LW",
      jerseyNumber: 9,
      dateOfBirth: "2000-01-31",
      heightCm: 180,
      weightKg: 75,
      preferredFoot: "BOTH",
      medicalStatus: "INJURED",
    });
  });

  it("always sends the medical status on create", () => {
    expect(formValuesToCreateBody({ ...EMPTY_PLAYER_FORM, ...valid })).toMatchObject({
      medicalStatus: "FIT",
    });
  });

  it("sends every emptied optional field as null on update, with the version", () => {
    const body = formValuesToUpdateBody(
      {
        ...full,
        secondaryPosition: "",
        jerseyNumber: "",
        heightCm: " ",
        weightKg: "",
        preferredFoot: "",
      },
      7,
    );

    expect(body).toEqual({
      fullName: "  Avi Cohen ",
      primaryPosition: "ST",
      secondaryPosition: null,
      jerseyNumber: null,
      dateOfBirth: "2000-01-31",
      heightCm: null,
      weightKg: null,
      preferredFoot: null,
      medicalStatus: "INJURED",
      version: 7,
    });
    // Explicitly present, not left out: a full replacement clears them.
    expect(Object.keys(body)).toHaveLength(10);
  });
});

describe("jerseyNumberError (the form's rule, and the re-activation dialog's)", () => {
  it.each(["", "  ", "1", "99", " 7 ", "07"])("accepts %j", (text) => {
    expect(jerseyNumberError(text)).toBeUndefined();
  });

  it.each(["0", "100", "-1", "1.5", "abc", "1e1", "٣", "0099"])("refuses %j", (text) => {
    expect(jerseyNumberError(text)).toBe("squad.form.errors.jerseyNumberRange");
  });

  it("is the rule validatePlayerForm applies", () => {
    for (const text of ["", "5", "0", "100", "x"]) {
      const errors = validatePlayerForm(
        { ...EMPTY_PLAYER_FORM, jerseyNumber: text },
        new Date(2026, 0, 1),
        "create",
      );
      expect(errors.jerseyNumber).toBe(jerseyNumberError(text));
    }
  });
});
