import { Radio } from "@base-ui/react/radio";
import { RadioGroup } from "@base-ui/react/radio-group";
import { useTranslation } from "react-i18next";
import { PLAYER_STATUS_KEYS } from "@/lib/squad/labels";
import { PLAYER_STATUSES, type PlayerStatus } from "@/lib/squad/types";
import { cn } from "@/lib/utils";
import { SEGMENTED_GROUP_CLASSES, SEGMENTED_OPTION_CLASSES } from "./segmentedControl";

function isPlayerStatus(value: unknown): value is PlayerStatus {
  return PLAYER_STATUSES.some((status) => status === value);
}

/**
 * The status segmented control (פעילים / משוחררים / הכל). A single choice that filters one table,
 * so a radio group, not tabs (there are no tab panels): Base UI's RadioGroup renders
 * role="radiogroup", each option role="radio" with aria-checked, one tab stop, and the arrow keys
 * move the choice (in the reading direction, from the DirectionProvider).
 */
export function StatusControl({
  value,
  onChange,
}: {
  value: PlayerStatus;
  onChange: (status: PlayerStatus) => void;
}) {
  const { t } = useTranslation();

  return (
    <RadioGroup
      aria-label={t("squad.statusLabel")}
      value={value}
      onValueChange={(next) => {
        if (isPlayerStatus(next)) {
          onChange(next);
        }
      }}
      className={SEGMENTED_GROUP_CLASSES}
    >
      {PLAYER_STATUSES.map((status) => (
        <Radio.Root
          key={status}
          value={status}
          className={cn(
            SEGMENTED_OPTION_CLASSES,
            "px-3 text-sm font-medium data-checked:font-semibold",
          )}
        >
          {t(PLAYER_STATUS_KEYS[status])}
        </Radio.Root>
      ))}
    </RadioGroup>
  );
}
