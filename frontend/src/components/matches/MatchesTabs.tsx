import { Radio } from "@base-ui/react/radio";
import { RadioGroup } from "@base-ui/react/radio-group";
import { useTranslation } from "react-i18next";
import {
  SEGMENTED_GROUP_CLASSES,
  SEGMENTED_OPTION_CLASSES,
} from "@/components/squad/segmentedControl";
import { MATCHES_TAB_KEYS } from "@/lib/matches/labels";
import { MATCHES_TABS, type MatchesTab } from "@/lib/matches/tab";
import { cn } from "@/lib/utils";

function isMatchesTab(value: unknown): value is MatchesTab {
  return MATCHES_TABS.some((tab) => tab === value);
}

/**
 * The matches screen's switch between the games and the league table: the squad toolbar's
 * segmented control and pattern (a Base UI RadioGroup: role="radiogroup", each option role="radio"
 * with aria-checked, one tab stop, arrow keys in the reading direction), with text options.
 */
export function MatchesTabs({
  value,
  onChange,
}: {
  value: MatchesTab;
  onChange: (tab: MatchesTab) => void;
}) {
  const { t } = useTranslation();

  return (
    <RadioGroup
      aria-label={t("matches.tabsLabel")}
      value={value}
      onValueChange={(next) => {
        if (isMatchesTab(next)) {
          onChange(next);
        }
      }}
      className={SEGMENTED_GROUP_CLASSES}
    >
      {MATCHES_TABS.map((tab) => (
        <Radio.Root
          key={tab}
          value={tab}
          className={cn(
            SEGMENTED_OPTION_CLASSES,
            "px-3.5 text-sm font-medium data-checked:font-semibold",
          )}
        >
          {t(MATCHES_TAB_KEYS[tab])}
        </Radio.Root>
      ))}
    </RadioGroup>
  );
}
