import { ageOn } from "./age";
import { isValidIsoDate, toIsoDate } from "./dates";
import type { MedicalStatus, Player, Position, PreferredFoot } from "./types";

/**
 * The player form's values, as the fields hold them: text for the numbers and the date, "" for an
 * empty field or select. Mapped to the API's bodies by formValuesToCreateBody /
 * formValuesToUpdateBody, from a player by playerToFormValues.
 */
export interface PlayerFormValues {
  fullName: string;
  primaryPosition: Position | "";
  secondaryPosition: Position | "";
  jerseyNumber: string;
  /** yyyy-MM-dd, as <input type="date"> holds it. */
  dateOfBirth: string;
  heightCm: string;
  weightKg: string;
  preferredFoot: PreferredFoot | "";
  medicalStatus: MedicalStatus | "";
}

export type PlayerFormField = keyof PlayerFormValues;

/** The fields in the form's order: the first invalid one is focused. */
export const PLAYER_FORM_FIELDS = [
  "fullName",
  "primaryPosition",
  "secondaryPosition",
  "jerseyNumber",
  "dateOfBirth",
  "heightCm",
  "weightKg",
  "preferredFoot",
  "medicalStatus",
] as const satisfies readonly PlayerFormField[];

export function isPlayerFormField(name: string): name is PlayerFormField {
  return (PLAYER_FORM_FIELDS as readonly string[]).includes(name);
}

/** An i18n key per invalid field. */
export type PlayerFormErrors = Partial<Record<PlayerFormField, string>>;

/** Create: medicalStatus is optional on the server (default FIT). Edit: it's required. */
export type PlayerFormMode = "create" | "edit";

/** A new player's form: empty, with the medical status preselected as the server's default. */
export const EMPTY_PLAYER_FORM: PlayerFormValues = {
  fullName: "",
  primaryPosition: "",
  secondaryPosition: "",
  jerseyNumber: "",
  dateOfBirth: "",
  heightCm: "",
  weightKg: "",
  preferredFoot: "",
  medicalStatus: "FIT",
};

/** The API's limits (squad.CreatePlayerRequest / UpdatePlayerRequest). */
export const FULL_NAME_MAX_LENGTH = 100;
export const JERSEY_NUMBER_RANGE = { min: 1, max: 99 } as const;
export const HEIGHT_CM_RANGE = { min: 140, max: 220 } as const;
export const WEIGHT_KG_RANGE = { min: 40, max: 150 } as const;
export const AGE_RANGE = { min: 18, max: 99 } as const;

/** POST /squad/players (squad.CreatePlayerRequest). */
export interface CreatePlayerBody {
  fullName: string;
  primaryPosition: Position | null;
  secondaryPosition: Position | null;
  jerseyNumber: number | null;
  dateOfBirth: string | null;
  heightCm: number | null;
  weightKg: number | null;
  preferredFoot: PreferredFoot | null;
  medicalStatus: MedicalStatus | null;
}

/**
 * PUT /squad/players/{id} (squad.UpdatePlayerRequest): a full replacement, so every optional field
 * is sent, null clearing it; `version` is the one the form was filled from.
 */
export interface UpdatePlayerBody extends CreatePlayerBody {
  version: number;
}

/** A player's values for the edit form; a null becomes "". */
export function playerToFormValues(player: Player): PlayerFormValues {
  const text = (value: number | string | null) => (value === null ? "" : String(value));
  return {
    fullName: player.fullName,
    primaryPosition: player.primaryPosition ?? "",
    secondaryPosition: player.secondaryPosition ?? "",
    jerseyNumber: text(player.jerseyNumber),
    dateOfBirth: player.dateOfBirth ?? "",
    heightCm: text(player.heightCm),
    weightKg: text(player.weightKg),
    preferredFoot: player.preferredFoot ?? "",
    medicalStatus: player.medicalStatus ?? "",
  };
}

/** A number field's value: null when empty. Called on validated values, so it's whole digits. */
export function toNumber(text: string): number | null {
  const trimmed = text.trim();
  return trimmed === "" ? null : Number(trimmed);
}

function orNull<T extends string>(value: T | ""): T | null {
  return value === "" ? null : value;
}

/**
 * The create body. Every field is sent, an empty one as null (the same as absent for a create);
 * numbers as numbers. The name is sent as typed: the server trims it.
 */
export function formValuesToCreateBody(values: PlayerFormValues): CreatePlayerBody {
  return {
    fullName: values.fullName,
    primaryPosition: orNull(values.primaryPosition),
    secondaryPosition: orNull(values.secondaryPosition),
    jerseyNumber: toNumber(values.jerseyNumber),
    dateOfBirth: orNull(values.dateOfBirth),
    heightCm: toNumber(values.heightCm),
    weightKg: toNumber(values.weightKg),
    preferredFoot: orNull(values.preferredFoot),
    medicalStatus: orNull(values.medicalStatus),
  };
}

/** The edit body: the create body plus `version`. An emptied optional field is sent as null. */
export function formValuesToUpdateBody(
  values: PlayerFormValues,
  version: number,
): UpdatePlayerBody {
  return { ...formValuesToCreateBody(values), version };
}

/** Whether `text` is a whole number, digits only (surrounding spaces ignored), within the range. */
function wholeNumberIn(text: string, range: { min: number; max: number }): boolean {
  const trimmed = text.trim();
  if (!/^\d{1,3}$/.test(trimmed)) {
    return false;
  }
  const value = Number(trimmed);
  return value >= range.min && value <= range.max;
}

/**
 * The jersey number field's error (an i18n key), or undefined when it's valid: optional, a whole
 * number 1–99 (squad.CreatePlayerRequest's @Min / @Max). Shared by the player form and the
 * re-activation dialog (squad.ReactivatePlayerRequest has the same rule).
 */
export function jerseyNumberError(text: string): string | undefined {
  return text.trim() !== "" && !wholeNumberIn(text, JERSEY_NUMBER_RANGE)
    ? "squad.form.errors.jerseyNumberRange"
    : undefined;
}

/**
 * The form's errors (an i18n key per invalid field), mirroring the API's validation exactly, so a
 * valid form is never refused with a 400:
 * - name: required (after trimming, as the server trims), at most 100 UTF-16 units (string.length,
 *   like @Size);
 * - primary position: required; secondary: optional, not the primary;
 * - jersey number 1–99, height 140–220, weight 40–150: optional, whole numbers;
 * - date of birth: required, a real yyyy-MM-dd date, not in the future, age 18–99 on `today`
 *   (@AdultAge, by ageOn, the server's rule);
 * - medical status: required on edit (on create the server defaults it).
 *
 * `today` is the browser's date; the server checks the age on its own, so around midnight the two
 * can differ by a day. The server's 400 then shows on the date field.
 */
export function validatePlayerForm(
  values: PlayerFormValues,
  today: Date,
  mode: PlayerFormMode,
): PlayerFormErrors {
  const errors: PlayerFormErrors = {};

  const name = values.fullName.trim();
  if (name === "") {
    errors.fullName = "squad.form.errors.required";
  } else if (name.length > FULL_NAME_MAX_LENGTH) {
    errors.fullName = "squad.form.errors.nameTooLong";
  }

  if (values.primaryPosition === "") {
    errors.primaryPosition = "squad.form.errors.required";
  }
  if (values.secondaryPosition !== "" && values.secondaryPosition === values.primaryPosition) {
    errors.secondaryPosition = "squad.form.errors.secondaryEqualsPrimary";
  }

  const jerseyNumber = jerseyNumberError(values.jerseyNumber);
  if (jerseyNumber !== undefined) {
    errors.jerseyNumber = jerseyNumber;
  }
  if (values.heightCm.trim() !== "" && !wholeNumberIn(values.heightCm, HEIGHT_CM_RANGE)) {
    errors.heightCm = "squad.form.errors.heightRange";
  }
  if (values.weightKg.trim() !== "" && !wholeNumberIn(values.weightKg, WEIGHT_KG_RANGE)) {
    errors.weightKg = "squad.form.errors.weightRange";
  }

  if (values.dateOfBirth === "") {
    errors.dateOfBirth = "squad.form.errors.required";
  } else if (!isValidIsoDate(values.dateOfBirth) || values.dateOfBirth > toIsoDate(today)) {
    errors.dateOfBirth = "squad.form.errors.invalidDate";
  } else {
    const age = ageOn(values.dateOfBirth, today);
    if (age === null || age < AGE_RANGE.min || age > AGE_RANGE.max) {
      errors.dateOfBirth = "squad.form.errors.ageRange";
    }
  }

  if (mode === "edit" && values.medicalStatus === "") {
    errors.medicalStatus = "squad.form.errors.required";
  }

  return errors;
}
