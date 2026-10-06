import type { ComponentProps, ReactNode } from "react";
import { FIELD_INVALID_CLASSES } from "@/components/form/fieldClasses";
import { FormField } from "@/components/form/FormField";
import { Input } from "@/components/ui/input";
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

/** A labeled auth-form input: a FormField around a 40px Input. */
export function AuthField({
  id,
  label,
  labelAction,
  hint,
  error,
  className,
  ...inputProps
}: AuthFieldProps) {
  return (
    <FormField id={id} label={label} labelAction={labelAction} hint={hint} error={error}>
      {(control) => (
        <Input {...control} className={cn(FIELD_INVALID_CLASSES, className)} {...inputProps} />
      )}
    </FormField>
  );
}
