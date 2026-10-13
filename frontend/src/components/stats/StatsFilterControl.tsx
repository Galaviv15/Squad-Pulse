import { Radio } from "@base-ui/react/radio";
import { RadioGroup } from "@base-ui/react/radio-group";
import { useTranslation } from "react-i18next";
import {
  SEGMENTED_GROUP_CLASSES,
  SEGMENTED_OPTION_CLASSES,
} from "@/components/squad/segmentedControl";
import type { PlayerStatsRow } from "@/lib/matches/playerStats";
import { STATS_FILTERS, statsLinkCounts, type StatsFilter } from "@/lib/matches/statsFilter";
import { cn } from "@/lib/utils";

function isStatsFilter(value: unknown): value is StatsFilter {
  return STATS_FILTERS.some((filter) => filter === value);
}

/**
 * The stats screen's filter (EDIT_FULL and up): the squad's segmented radio group ("הצגה"), all
 * rows or the ones waiting for a link (suggested ones included), each with its count; beside it
 * the muted "a מקושרים · b עם הצעה · c ללא קישור".
 */
export function StatsFilterControl({
  rows,
  value,
  onChange,
}: {
  rows: PlayerStatsRow[];
  value: StatsFilter;
  onChange: (filter: StatsFilter) => void;
}) {
  const { t } = useTranslation();
  const counts = statsLinkCounts(rows);
  const labels: Record<StatsFilter, string> = {
    all: t("stats.filter.all", { count: rows.length }),
    unlinked: t("stats.filter.unlinked", { count: counts.suggested + counts.unlinked }),
  };

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
      <RadioGroup
        aria-label={t("stats.filter.label")}
        value={value}
        onValueChange={(next) => {
          if (isStatsFilter(next)) {
            onChange(next);
          }
        }}
        className={SEGMENTED_GROUP_CLASSES}
      >
        {STATS_FILTERS.map((filter) => (
          <Radio.Root
            key={filter}
            value={filter}
            className={cn(
              SEGMENTED_OPTION_CLASSES,
              "px-3 text-sm font-medium data-checked:font-semibold",
            )}
          >
            {labels[filter]}
          </Radio.Root>
        ))}
      </RadioGroup>
      <p className="text-[0.8125rem] text-muted-foreground">
        {[
          t("stats.counts.linked", { count: counts.linked }),
          t("stats.counts.suggested", { count: counts.suggested }),
          t("stats.counts.unlinked", { count: counts.unlinked }),
        ].join(" · ")}
      </p>
    </div>
  );
}
