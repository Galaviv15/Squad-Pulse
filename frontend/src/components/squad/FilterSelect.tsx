import { useId, type ReactNode } from "react";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

export interface FilterOption<T extends string> {
  value: T;
  label: ReactNode;
}

interface FilterSelectProps<T extends string> {
  /** The visible label, which also names the control. */
  label: string;
  /** The first option, for no filter ("הכל"). */
  anyLabel: string;
  options: FilterOption<T>[];
  /** undefined: no filter. */
  value: T | undefined;
  onChange: (value: T | undefined) => void;
}

/**
 * One of the filter bar's selects: a visible 13/500 label beside a 36px Base UI select (the
 * trigger is role="combobox", named by the label through aria-labelledby; the options are
 * role="option" in a listbox). The "any" option is the null value.
 */
export function FilterSelect<T extends string>({
  label,
  anyLabel,
  options,
  value,
  onChange,
}: FilterSelectProps<T>) {
  const labelId = useId();
  const items: { value: T | null; label: ReactNode }[] = [
    { value: null, label: anyLabel },
    ...options,
  ];

  return (
    <div className="flex items-center gap-2">
      <span id={labelId} className="text-[0.8125rem] font-medium">
        {label}
      </span>
      <Select<T | null>
        items={items}
        value={value ?? null}
        onValueChange={(next) => onChange(next ?? undefined)}
      >
        <SelectTrigger size="sm" aria-labelledby={labelId} className="min-w-24 bg-card">
          <SelectValue />
        </SelectTrigger>
        <SelectContent>
          {items.map((item) => (
            <SelectItem key={item.value ?? ""} value={item.value}>
              {item.label}
            </SelectItem>
          ))}
        </SelectContent>
      </Select>
    </div>
  );
}
