import { useId, useRef, useState, type FormEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { FIELD_INVALID_CLASSES } from "@/components/form/fieldClasses";
import { FormField, type FormControlProps } from "@/components/form/FormField";
import { FormAlert } from "@/components/form/FormMessage";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { ApiError } from "@/lib/api/errors";
import { toIsoDate } from "@/lib/squad/dates";
import { PLAYER_ERROR_CODES } from "@/lib/squad/errorCodes";
import {
  isPlayerFormField,
  PLAYER_FORM_FIELDS,
  validatePlayerForm,
  type PlayerFormErrors,
  type PlayerFormField,
  type PlayerFormMode,
  type PlayerFormValues,
} from "@/lib/squad/form";
import { MEDICAL_STATUS_KEYS, PREFERRED_FOOT_KEYS } from "@/lib/squad/labels";
import { playerPath } from "@/lib/squad/paths";
import { squadReturnPath } from "@/lib/squad/squadReturnPath";
import {
  MEDICAL_STATUSES,
  POSITIONS,
  PREFERRED_FEET,
  type Player,
  type Position,
} from "@/lib/squad/types";
import { cn } from "@/lib/utils";

/** A form-level outcome of a failed save, shown in the alert above the fields. */
type SaveAlert =
  "invalid" | "failed" | "forbidden" | "notFound" | "stale" | "released" | "reloadFailed";

interface PlayerFormProps {
  mode: PlayerFormMode;
  /** The values the form starts from; later changes are ignored (remount it with a new key). */
  initialValues: PlayerFormValues;
  /** Sends the values: resolves with the saved player, rejects with the request's error. */
  save: (values: PlayerFormValues) => Promise<Player>;
  /** After a successful save (the page navigates away). */
  onSaved: (player: Player) => void;
  /** Where "ביטול" goes. */
  cancelTo: string;
  /** Edit: the player, for the released alert's link to its card. */
  playerId?: string;
  /**
   * Edit: after a stale-version 409, "טעינת הגרסה העדכנית" calls this; the page reloads the player
   * and remounts the form with its values. Resolves false if the reload failed.
   */
  onReloadLatest?: () => Promise<boolean>;
}

/** A field's control id, from the form's own id. */
const fieldId = (formId: string, field: PlayerFormField) => `${formId}-${field}`;

/**
 * The player form, for both adding (mode "create") and editing (mode "edit") a player. Validated
 * on submit by validatePlayerForm, and as the user types after the first submit (the secondary ≠
 * primary rule always: it's a clash between two choices, not an unfinished entry). The first
 * invalid field is focused. A second submit while one is saving is dropped (a ref, so even a
 * double click in one frame sends one request).
 *
 * The server's answers: a 400's field errors on their fields (a generic translated text: never the
 * backend's English), other 400 details as an alert; 409s by their code (jersey number taken → on
 * its field; stale version → an alert with "load the latest version"; released → an alert linking
 * to the card; an unknown code → the generic alert); 403, 404, 5xx and no response → an alert.
 * The user's input is always kept.
 */
export function PlayerForm({
  mode,
  initialValues,
  save,
  onSaved,
  cancelTo,
  playerId,
  onReloadLatest,
}: PlayerFormProps) {
  const { t } = useTranslation();
  const formId = useId();
  const [today] = useState(() => new Date());
  const [values, setValues] = useState(initialValues);
  const [submitted, setSubmitted] = useState(false);
  const [serverErrors, setServerErrors] = useState<PlayerFormErrors>({});
  const [alert, setAlert] = useState<SaveAlert | null>(null);
  const [pending, setPending] = useState(false);
  const [reloading, setReloading] = useState(false);
  const submitting = useRef(false);

  const clientErrors = validatePlayerForm(values, today, mode);
  const liveErrors: PlayerFormErrors = submitted
    ? clientErrors
    : { secondaryPosition: clientErrors.secondaryPosition };
  const errors: PlayerFormErrors = { ...serverErrors };
  for (const field of PLAYER_FORM_FIELDS) {
    if (liveErrors[field] !== undefined) {
      errors[field] = liveErrors[field];
    }
  }

  function focusField(field: PlayerFormField) {
    document.getElementById(fieldId(formId, field))?.focus();
  }

  function change<F extends PlayerFormField>(field: F, value: PlayerFormValues[F]) {
    setValues((previous) => ({ ...previous, [field]: value }));
    // A server error was about the value sent; the user has changed it.
    setServerErrors((previous) => {
      const next = { ...previous };
      delete next[field];
      return next;
    });
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) {
      return;
    }
    setSubmitted(true);
    const firstInvalid = PLAYER_FORM_FIELDS.find((field) => clientErrors[field] !== undefined);
    if (firstInvalid !== undefined) {
      setServerErrors({});
      focusField(firstInvalid);
      return;
    }
    submitting.current = true;
    setPending(true);
    setAlert(null);
    setServerErrors({});
    let saved: Player;
    try {
      saved = await save(values);
    } catch (error) {
      submitting.current = false;
      setPending(false);
      showSaveError(error);
      return;
    }
    // Still "submitting": the page navigates away, so nothing can be sent again.
    onSaved(saved);
  }

  function showSaveError(error: unknown) {
    if (!(error instanceof ApiError)) {
      setAlert("failed");
      return;
    }
    switch (error.status) {
      case 400: {
        const fieldErrors: PlayerFormErrors = {};
        let unexplained = error.generalErrors.length > 0 || error.details.length === 0;
        for (const name of Object.keys(error.fieldErrors)) {
          if (isPlayerFormField(name)) {
            fieldErrors[name] = "squad.form.errors.invalidValue";
          } else {
            unexplained = true;
          }
        }
        setServerErrors(fieldErrors);
        if (unexplained) {
          setAlert("invalid");
        }
        const first = PLAYER_FORM_FIELDS.find((field) => fieldErrors[field] !== undefined);
        if (first !== undefined) {
          focusField(first);
        }
        return;
      }
      case 403:
        setAlert("forbidden");
        return;
      case 404:
        setAlert(mode === "edit" ? "notFound" : "failed");
        return;
      case 409:
        switch (error.code) {
          case PLAYER_ERROR_CODES.JERSEY_NUMBER_TAKEN:
            setServerErrors({ jerseyNumber: "squad.form.errors.jerseyNumberTaken" });
            focusField("jerseyNumber");
            return;
          case PLAYER_ERROR_CODES.STALE_VERSION:
            setAlert("stale");
            return;
          case PLAYER_ERROR_CODES.PLAYER_RELEASED:
            setAlert("released");
            return;
          default:
            setAlert("invalid");
            return;
        }
      default:
        setAlert("failed");
    }
  }

  async function reloadLatest() {
    if (onReloadLatest === undefined || reloading) {
      return;
    }
    setReloading(true);
    // On success the page remounts this form with the new values; nothing more to do here.
    const reloaded = await onReloadLatest();
    if (!reloaded) {
      setReloading(false);
      setAlert("reloadFailed");
    }
  }

  const field = (name: PlayerFormField) => ({
    id: fieldId(formId, name),
    label: t(`squad.fields.${name}`),
    error: errors[name],
  });

  const numberInput = (name: "jerseyNumber" | "heightCm" | "weightKg") => (
    <FormField {...field(name)}>
      {(control) => (
        <Input
          {...control}
          inputMode="numeric"
          autoComplete="off"
          dir="ltr"
          className={cn("tabular-nums", FIELD_INVALID_CLASSES)}
          value={values[name]}
          onChange={(event) => change(name, event.target.value)}
        />
      )}
    </FormField>
  );

  const positionItems = POSITIONS.map((position) => ({
    value: position,
    label: <span dir="ltr">{position}</span>,
  }));

  return (
    <form
      noValidate
      onSubmit={(event) => void submit(event)}
      className="flex flex-col gap-5 rounded-lg border border-border bg-card p-5 md:p-6"
    >
      {alert !== null && (
        <SaveAlertMessage
          alert={alert}
          playerId={playerId}
          reloading={reloading}
          onReload={() => void reloadLatest()}
        />
      )}
      <div className="grid grid-cols-1 gap-x-5 gap-y-4 md:grid-cols-2">
        <FormField {...field("fullName")} required className="md:col-span-2">
          {(control) => (
            <Input
              {...control}
              autoComplete="off"
              className={FIELD_INVALID_CLASSES}
              value={values.fullName}
              onChange={(event) => change("fullName", event.target.value)}
            />
          )}
        </FormField>
        <FormField {...field("primaryPosition")} required>
          {(control) => (
            <SelectControl<Position>
              control={control}
              items={positionItems}
              value={values.primaryPosition}
              onChange={(value) => change("primaryPosition", value)}
            />
          )}
        </FormField>
        <FormField {...field("secondaryPosition")}>
          {(control) => (
            <SelectControl<Position>
              control={control}
              noneLabel={t("squad.form.none")}
              items={positionItems}
              value={values.secondaryPosition}
              onChange={(value) => change("secondaryPosition", value)}
            />
          )}
        </FormField>
        {numberInput("jerseyNumber")}
        <FormField {...field("dateOfBirth")} required>
          {(control) => (
            <Input
              {...control}
              type="date"
              dir="ltr"
              max={toIsoDate(today)}
              className={cn("tabular-nums", FIELD_INVALID_CLASSES)}
              value={values.dateOfBirth}
              onChange={(event) => change("dateOfBirth", event.target.value)}
            />
          )}
        </FormField>
        {numberInput("heightCm")}
        {numberInput("weightKg")}
        <FormField {...field("preferredFoot")}>
          {(control) => (
            <SelectControl
              control={control}
              noneLabel={t("squad.form.none")}
              items={PREFERRED_FEET.map((foot) => ({
                value: foot,
                label: t(PREFERRED_FOOT_KEYS[foot]),
              }))}
              value={values.preferredFoot}
              onChange={(value) => change("preferredFoot", value)}
            />
          )}
        </FormField>
        <FormField {...field("medicalStatus")} required>
          {(control) => (
            <SelectControl
              control={control}
              items={MEDICAL_STATUSES.map((status) => ({
                value: status,
                label: t(MEDICAL_STATUS_KEYS[status]),
              }))}
              value={values.medicalStatus}
              onChange={(value) => change("medicalStatus", value)}
            />
          )}
        </FormField>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" disabled={pending} className="font-semibold">
          {pending
            ? t("squad.form.saving")
            : t(mode === "create" ? "squad.form.submitCreate" : "squad.form.submitEdit")}
        </Button>
        <Link to={cancelTo} className={buttonVariants({ variant: "outline" })}>
          {t("squad.form.cancel")}
        </Link>
      </div>
    </form>
  );
}

interface SelectControlProps<T extends string> {
  control: FormControlProps;
  items: { value: T; label: ReactNode }[];
  /** The first option, for no value (""), on an optional select. */
  noneLabel?: string;
  value: T | "";
  onChange: (value: T | "") => void;
}

/**
 * A 40px Base UI select for a form field (role="combobox", named by the field's label through
 * aria-labelledby). "" is no value: the "ללא" option when there's one, otherwise an empty trigger.
 */
function SelectControl<T extends string>({
  control,
  items,
  noneLabel,
  value,
  onChange,
}: SelectControlProps<T>) {
  const allItems: { value: T | null; label: ReactNode }[] =
    noneLabel === undefined ? items : [{ value: null, label: noneLabel }, ...items];

  return (
    <Select<T | null>
      items={allItems}
      value={value === "" ? null : value}
      onValueChange={(next) => onChange(next ?? "")}
    >
      <SelectTrigger {...control} className={cn("w-full bg-card", FIELD_INVALID_CLASSES)}>
        <SelectValue />
      </SelectTrigger>
      <SelectContent>
        {allItems.map((item) => (
          <SelectItem key={item.value ?? ""} value={item.value}>
            {item.label}
          </SelectItem>
        ))}
      </SelectContent>
    </Select>
  );
}

function SaveAlertMessage({
  alert,
  playerId,
  reloading,
  onReload,
}: {
  alert: SaveAlert;
  playerId: string | undefined;
  reloading: boolean;
  onReload: () => void;
}) {
  const { t } = useTranslation();
  const linkClass = "w-fit underline underline-offset-4 hover:no-underline";

  switch (alert) {
    case "invalid":
      return <FormAlert>{t("squad.form.saveInvalid")}</FormAlert>;
    case "failed":
      return <FormAlert>{t("squad.form.saveFailed")}</FormAlert>;
    case "forbidden":
      return <FormAlert>{t("squad.noPermission")}</FormAlert>;
    case "reloadFailed":
      return <FormAlert>{t("squad.player.loadError")}</FormAlert>;
    case "notFound":
      return (
        <FormAlert
          action={
            <Link to={squadReturnPath()} className={linkClass}>
              {t("squad.backToSquad")}
            </Link>
          }
        >
          {t("squad.player.notFound")}
        </FormAlert>
      );
    case "released":
      return (
        <FormAlert
          action={
            playerId !== undefined && (
              <Link to={playerPath(playerId)} className={linkClass}>
                {t("squad.form.toCard")}
              </Link>
            )
          }
        >
          {t("squad.form.released")}
        </FormAlert>
      );
    case "stale":
      return (
        <FormAlert
          action={
            <>
              <p className="font-normal">{t("squad.form.stale.hint")}</p>
              <Button
                type="button"
                variant="outline"
                size="sm"
                className="mt-1 w-fit"
                disabled={reloading}
                onClick={onReload}
              >
                {t("squad.form.stale.reload")}
              </Button>
            </>
          }
        >
          {t("squad.form.stale.message")}
        </FormAlert>
      );
  }
}
