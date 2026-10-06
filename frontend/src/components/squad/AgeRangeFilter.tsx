import { useEffect, useId, useRef, useState, type KeyboardEvent } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { MAX_AGE, MIN_AGE, parseAge } from "@/lib/squad/filters";
import { cn } from "@/lib/utils";

/** How long typing must pause before the ages are applied. */
export const AGE_DEBOUNCE_MS = 500;

export interface AgeRange {
  minAge?: number;
  maxAge?: number;
}

type AgeError = { kind: "range"; min: boolean; max: boolean } | { kind: "order" };

type Evaluated = { ok: true; range: AgeRange } | { ok: false; error: AgeError };

/** One field's text: empty = no bound, otherwise a whole number MIN_AGE–MAX_AGE, or invalid (null). */
function fieldValue(text: string): number | undefined | null {
  const trimmed = text.trim();
  return trimmed === "" ? undefined : (parseAge(trimmed) ?? null);
}

function evaluate(minText: string, maxText: string): Evaluated {
  const minAge = fieldValue(minText);
  const maxAge = fieldValue(maxText);
  if (minAge === null || maxAge === null) {
    return { ok: false, error: { kind: "range", min: minAge === null, max: maxAge === null } };
  }
  if (minAge !== undefined && maxAge !== undefined && minAge > maxAge) {
    return { ok: false, error: { kind: "order" } };
  }
  return { ok: true, range: { minAge, maxAge } };
}

const text = (age: number | undefined) => (age === undefined ? "" : String(age));

/**
 * The age range: "מגיל" / "עד גיל". What's typed is applied (onApply) on Enter, on blur, or after
 * a pause of AGE_DEBOUNCE_MS, and only when valid: an invalid value (not a whole number, outside
 * 18–99, or a minimum above the maximum) shows an inline error instead, and the list keeps the
 * last valid filters. An empty field removes its bound. When the applied ages change from outside
 * (the URL), the fields follow them; the filter bar remounts this (a new key) on "clear", which
 * also drops an invalid draft.
 *
 * Text fields with a numeric keyboard rather than type="number": a number field reports text it
 * can't parse ("1e") as an empty value, which would silently remove the bound instead of showing
 * the error, and it changes the value on a mouse wheel.
 */
export function AgeRangeFilter({
  minAge,
  maxAge,
  onApply,
}: AgeRange & { onApply: (range: AgeRange) => void }) {
  const { t } = useTranslation();
  const minId = useId();
  const maxId = useId();
  const errorId = useId();
  const [minText, setMinText] = useState(text(minAge));
  const [maxText, setMaxText] = useState(text(maxAge));
  const [error, setError] = useState<AgeError | null>(null);
  const [applied, setApplied] = useState<AgeRange>({ minAge, maxAge });
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // The applied ages changed (this field's own apply, or the URL): show them. Done while
  // rendering, React's pattern for state that follows a prop.
  if (applied.minAge !== minAge || applied.maxAge !== maxAge) {
    setApplied({ minAge, maxAge });
    setMinText(text(minAge));
    setMaxText(text(maxAge));
    setError(null);
  }

  // A pending apply is dropped when the applied ages change, and on unmount, so a draft from
  // before never overwrites them.
  useEffect(() => () => clearTimeout(timer.current), [minAge, maxAge]);

  function apply(nextMin: string, nextMax: string) {
    clearTimeout(timer.current);
    const result = evaluate(nextMin, nextMax);
    if (!result.ok) {
      setError(result.error);
      return;
    }
    setError(null);
    if (result.range.minAge !== minAge || result.range.maxAge !== maxAge) {
      onApply(result.range);
    }
  }

  function schedule(nextMin: string, nextMax: string) {
    clearTimeout(timer.current);
    timer.current = setTimeout(() => apply(nextMin, nextMax), AGE_DEBOUNCE_MS);
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "Enter") {
      apply(minText, maxText);
    }
  }

  const minInvalid = error !== null && (error.kind === "order" || error.min);
  const maxInvalid = error !== null && (error.kind === "order" || error.max);
  const message =
    error === null
      ? null
      : error.kind === "order"
        ? t("squad.filters.ageOrder")
        : t("squad.filters.ageOutOfRange", { min: MIN_AGE, max: MAX_AGE });

  const inputClass = "w-[72px] bg-card tabular-nums";

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <Label htmlFor={minId}>{t("squad.filters.minAge")}</Label>
        <Input
          id={minId}
          inputMode="numeric"
          autoComplete="off"
          size="sm"
          value={minText}
          aria-invalid={minInvalid || undefined}
          aria-describedby={minInvalid ? errorId : undefined}
          className={inputClass}
          onChange={(event) => {
            setMinText(event.target.value);
            schedule(event.target.value, maxText);
          }}
          onBlur={() => apply(minText, maxText)}
          onKeyDown={onKeyDown}
        />
        <Label htmlFor={maxId} className="ms-1">
          {t("squad.filters.maxAge")}
        </Label>
        <Input
          id={maxId}
          inputMode="numeric"
          autoComplete="off"
          size="sm"
          value={maxText}
          aria-invalid={maxInvalid || undefined}
          aria-describedby={maxInvalid ? errorId : undefined}
          className={inputClass}
          onChange={(event) => {
            setMaxText(event.target.value);
            schedule(minText, event.target.value);
          }}
          onBlur={() => apply(minText, maxText)}
          onKeyDown={onKeyDown}
        />
      </div>
      <p
        id={errorId}
        aria-live="polite"
        className={cn("text-xs text-danger", message === null && "sr-only")}
      >
        {message}
      </p>
    </div>
  );
}
