import { Radio } from "@base-ui/react/radio";
import { RadioGroup } from "@base-ui/react/radio-group";
import { useTranslation } from "react-i18next";
import { PLAYER_STATUS_KEYS } from "@/lib/squad/labels";
import { PLAYER_STATUSES, type PlayerStatus } from "@/lib/squad/types";

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
      className="inline-flex h-9 items-stretch gap-0.5 rounded-lg bg-muted p-[3px]"
    >
      {PLAYER_STATUSES.map((status) => (
        <Radio.Root
          key={status}
          value={status}
          className="flex cursor-pointer items-center rounded-md border border-transparent px-3 text-sm font-medium text-muted-foreground transition-colors outline-none select-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 data-checked:border-border data-checked:bg-card data-checked:font-semibold data-checked:text-foreground"
        >
          {t(PLAYER_STATUS_KEYS[status])}
        </Radio.Root>
      ))}
    </RadioGroup>
  );
}
