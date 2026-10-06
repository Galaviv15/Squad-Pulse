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
  /** The control's id; the label is `${id}-label`, the error `${id}-error`. */
  id: string;
  label: string;
  /** Marked with a visual "*" and aria-required on the control. */
  required?: boolean;
  /** The i18n key of the field's error, if it's invalid. */
  error?: string;
  className?: string;
  children: (control: FormControlProps) => ReactNode;
}

/**
 * A labeled form field (AuthField's pattern, for any control): the 13/500 label, the control, and
 * the error under it, linked by aria-describedby; an error also marks the control aria-invalid
 * (the controls draw that with a --danger border).
 */
export function FormField({ id, label, required, error, className, children }: FormFieldProps) {
  const { t } = useTranslation();
  const labelId = `${id}-label`;
  const errorId = `${id}-error`;

  return (
    <div className={cn("flex flex-col gap-1.5", className)}>
      <Label id={labelId} htmlFor={id} className="gap-1">
        {label}
        {required && (
          <span aria-hidden="true" className="text-danger">
            *
          </span>
        )}
      </Label>
      {children({
        id,
        "aria-invalid": error ? true : undefined,
        "aria-describedby": error ? errorId : undefined,
        "aria-required": required || undefined,
        "aria-labelledby": labelId,
      })}
      {error && (
        <p id={errorId} className="text-[0.8125rem] text-danger">
          {t(error)}
        </p>
      )}
    </div>
  );
}
