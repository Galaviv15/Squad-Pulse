import { Radio } from "@base-ui/react/radio";
import { RadioGroup } from "@base-ui/react/radio-group";
import { LayoutGrid, List, type LucideIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { SQUAD_VIEW_KEYS } from "@/lib/squad/labels";
import { cn } from "@/lib/utils";
import { SQUAD_VIEWS, type SquadView } from "@/lib/squad/view";
import { SEGMENTED_GROUP_CLASSES, SEGMENTED_OPTION_CLASSES } from "./segmentedControl";

const VIEW_ICONS: Record<SquadView, LucideIcon> = { list: List, cards: LayoutGrid };

function isSquadView(value: unknown): value is SquadView {
  return SQUAD_VIEWS.some((view) => view === value);
}

/**
 * The squad page's view toggle (רשימה / כרטיסיות): the status control's segmented look and
 * pattern (a Base UI RadioGroup: role="radiogroup", each option role="radio" with aria-checked,
 * one tab stop, arrow keys in the reading direction), with icon-only options, each named by its
 * aria-label and shown as a tooltip by its title.
 */
export function ViewToggle({
  value,
  onChange,
}: {
  value: SquadView;
  onChange: (view: SquadView) => void;
}) {
  const { t } = useTranslation();

  return (
    <RadioGroup
      aria-label={t("squad.viewLabel")}
      value={value}
      onValueChange={(next) => {
        if (isSquadView(next)) {
          onChange(next);
        }
      }}
      className={SEGMENTED_GROUP_CLASSES}
    >
      {SQUAD_VIEWS.map((view) => {
        const Icon = VIEW_ICONS[view];
        const label = t(SQUAD_VIEW_KEYS[view]);
        return (
          <Radio.Root
            key={view}
            value={view}
            aria-label={label}
            title={label}
            className={cn(SEGMENTED_OPTION_CLASSES, "w-9 justify-center")}
          >
            <Icon aria-hidden="true" className="size-[18px]" />
          </Radio.Root>
        );
      })}
    </RadioGroup>
  );
}
