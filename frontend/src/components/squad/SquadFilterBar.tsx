import { X } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { hasFilterBarFilters, type SquadFilters } from "@/lib/squad/filters";
import { MEDICAL_STATUS_KEYS, PREFERRED_FOOT_KEYS } from "@/lib/squad/labels";
import { MEDICAL_STATUSES, POSITIONS, PREFERRED_FEET } from "@/lib/squad/types";
import { AgeRangeFilter } from "./AgeRangeFilter";
import { FilterSelect } from "./FilterSelect";

interface SquadFilterBarProps {
  filters: SquadFilters;
  setFilters: (patch: Partial<SquadFilters>) => void;
  onClear: () => void;
  /** Changes on every "clear", so the age fields start over (an invalid draft included). */
  clearCount: number;
}

/**
 * The filter bar: primary position, age range, medical status, foot, and "clear", which removes
 * those four (never status) and is disabled while none is set. Every control is 36px, with a
 * visible label.
 */
export function SquadFilterBar({ filters, setFilters, onClear, clearCount }: SquadFilterBarProps) {
  const { t } = useTranslation();
  const any = t("squad.filters.any");

  return (
    <div className="flex flex-wrap items-start gap-3 rounded-lg border border-border bg-card px-4 py-3">
      <FilterSelect
        label={t("squad.filters.position")}
        anyLabel={any}
        options={POSITIONS.map((position) => ({
          value: position,
          label: <span dir="ltr">{position}</span>,
        }))}
        value={filters.position}
        onChange={(position) => setFilters({ position })}
      />
      <AgeRangeFilter
        key={clearCount}
        minAge={filters.minAge}
        maxAge={filters.maxAge}
        onApply={setFilters}
      />
      <FilterSelect
        label={t("squad.filters.medicalStatus")}
        anyLabel={any}
        options={MEDICAL_STATUSES.map((status) => ({
          value: status,
          label: t(MEDICAL_STATUS_KEYS[status]),
        }))}
        value={filters.medicalStatus}
        onChange={(medicalStatus) => setFilters({ medicalStatus })}
      />
      <FilterSelect
        label={t("squad.filters.preferredFoot")}
        anyLabel={any}
        options={PREFERRED_FEET.map((foot) => ({
          value: foot,
          label: t(PREFERRED_FOOT_KEYS[foot]),
        }))}
        value={filters.preferredFoot}
        onChange={(preferredFoot) => setFilters({ preferredFoot })}
      />
      <Button variant="ghost" size="sm" disabled={!hasFilterBarFilters(filters)} onClick={onClear}>
        <X aria-hidden="true" />
        {t("squad.filters.clear")}
      </Button>
    </div>
  );
}
