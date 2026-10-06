import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

/** The ARIA props a FormField's control gets: spread them onto the input or select trigger. */
export interface FormControlProps {
  id: string;
  "aria-invalid": true | undefined;
  "aria-describedby": string | undefined;
  "aria-required": true | undefined;
  /** For a control that isn't labelable by <label for> alone (a select trigger). */
  "aria-labelledby": string;
}

interface FormFieldProps {
  /** The control's id; the label is `${id}-label`, the hint `${id}-hint`, the error `${id}-error`. */
  id: string;
  label: string;
  /** Shown beside the label, at the end of its row (e.g. the "forgot password" link). */
  labelAction?: ReactNode;
  /** Marked with a visual "*" (aria-hidden) and aria-required on the control. */
  required?: boolean;
  /** A hint under the control; an error replaces it. */
  hint?: string;
  /** The i18n key of the field's error, if it's invalid. */
  error?: string;
  className?: string;
  children: (control: FormControlProps) => ReactNode;
}

/**
 * A labeled form field, for any control (the auth screens' AuthField is a thin wrapper over it):
 * the 13/500 label, the control, and the hint or error under it, linked by aria-describedby; an
 * error also marks the control aria-invalid (drawn with FIELD_INVALID_CLASSES' --danger border).
 */
export function FormField({
  id,
  label,
  labelAction,
  required,
  hint,
  error,
  className,
  children,
}: FormFieldProps) {
  const { t } = useTranslation();
  const labelId = `${id}-label`;
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;

  const labelElement = (
    <Label id={labelId} htmlFor={id} className="gap-1">
      {label}
      {required && (
        <span aria-hidden="true" className="text-danger">
          *
        </span>
      )}
    </Label>
  );

  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      {labelAction === undefined ? (
        labelElement
      ) : (
        <div className="flex items-center justify-between gap-2">
          {labelElement}
          {labelAction}
        </div>
      )}
      {children({
        id,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": error ? errorId : hint ? hintId : undefined,
        "aria-required": required || undefined,
        "aria-labelledby": labelId,
      })}
      {error ? (
        <p id={errorId} className="text-[0.8125rem] text-danger">
          {t(error)}
        </p>
      ) : (
        hint && (
          <p id={hintId} className="text-xs text-muted-foreground">
            {hint}
          </p>
        )
      )}
    </div>
  );
}
