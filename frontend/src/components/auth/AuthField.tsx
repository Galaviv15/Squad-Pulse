import type { ComponentProps, ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { cn } from "@/lib/utils";

interface AuthFieldProps extends Omit<ComponentProps<typeof Input>, "id"> {
  id: string;
  label: string;
  /** Shown beside the label, at the end of its row (the "forgot password" link). */
  labelAction?: ReactNode;
  /** A hint under the input; an error replaces it. */
  hint?: string;
  /** The i18n key of the field's error, if it's invalid. */
  error?: string;
}

/**
 * A labeled auth-form input. The hint or error under it is linked with aria-describedby, and an
 * error marks the input aria-invalid with a --danger border.
 */
export function AuthField({
  id,
  label,
  labelAction,
  hint,
  error,
  className,
  ...inputProps
}: AuthFieldProps) {
  const { t } = useTranslation();
  const describedBy = error ? `${id}-error` : hint ? `${id}-hint` : undefined;

  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex items-center justify-between gap-2">
        <Label htmlFor={id}>{label}</Label>
        {labelAction}
      </div>
      <Input
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          "aria-invalid:border-danger aria-invalid:ring-danger/20 focus-visible:aria-invalid:border-danger",
          className,
        )}
        {...inputProps}
      />
      {error ? (
        <p id={`${id}-error`} className="text-[0.8125rem] text-danger">
          {t(error)}
        </p>
      ) : (
        hint && (
          <p id={`${id}-hint`} className="text-xs text-muted-foreground">
            {hint}
          </p>
        )
      )}
    </div>
  );
}
