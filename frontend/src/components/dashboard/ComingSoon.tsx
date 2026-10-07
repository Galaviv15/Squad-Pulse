import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

/*
 * The dashboard's "coming soon" placeholders (KAN-44): a dashed --input border on a transparent
 * background, the title, a muted line and a muted "בקרוב" pill. No data, no links, nothing
 * focusable: they only say what will be there. Plain markup, no request.
 */

const PLACEHOLDER_CLASSES = "flex flex-col gap-2 rounded-lg border border-dashed border-input p-5";

function PlaceholderHeader({ title }: { title: string }) {
  const { t } = useTranslation();
  return (
    <div className="flex items-center justify-between gap-3">
      <h2 className="text-base font-semibold">{title}</h2>
      <Badge variant="muted">{t("dashboard.comingSoon")}</Badge>
    </div>
  );
}

/** Next training, next match, league table: the row of small placeholders. */
const CARDS = [
  {
    titleKey: "dashboard.placeholders.training.title",
    bodyKey: "dashboard.placeholders.training.body",
  },
  { titleKey: "dashboard.placeholders.match.title", bodyKey: "dashboard.placeholders.match.body" },
  {
    titleKey: "dashboard.placeholders.league.title",
    bodyKey: "dashboard.placeholders.league.body",
  },
] as const;

export function ComingSoonCards() {
  const { t } = useTranslation();
  return (
    <div className="grid grid-cols-[repeat(auto-fit,minmax(min(260px,100%),1fr))] gap-5">
      {CARDS.map(({ titleKey, bodyKey }) => (
        <div
          key={titleKey}
          data-slot="coming-soon"
          className={cn(PLACEHOLDER_CLASSES, "min-h-[140px]")}
        >
          <PlaceholderHeader title={t(titleKey)} />
          <p className="text-sm text-muted-foreground">{t(bodyKey)}</p>
        </div>
      ))}
    </div>
  );
}

/** The week's days, Sunday first: the first column sits at the start side (right in RTL). */
const DAY_KEYS = [
  "dashboard.weekly.days.sunday",
  "dashboard.weekly.days.monday",
  "dashboard.weekly.days.tuesday",
  "dashboard.weekly.days.wednesday",
  "dashboard.weekly.days.thursday",
  "dashboard.weekly.days.friday",
  "dashboard.weekly.days.saturday",
] as const;

/**
 * The wide weekly-schedule placeholder: seven empty day columns. Below 7 × 90px they scroll
 * sideways inside the card, never the page.
 */
export function WeeklySchedulePlaceholder() {
  const { t } = useTranslation();
  return (
    <div data-slot="coming-soon" className={cn(PLACEHOLDER_CLASSES, "gap-4")}>
      <PlaceholderHeader title={t("dashboard.weekly.title")} />
      <div className="overflow-x-auto">
        <ol className="grid grid-cols-[repeat(7,minmax(90px,1fr))] gap-3">
          {DAY_KEYS.map((key) => (
            <DayColumn key={key}>{t(key)}</DayColumn>
          ))}
        </ol>
      </div>
      <p className="text-[0.8125rem] text-muted-foreground">{t("dashboard.weekly.caption")}</p>
    </div>
  );
}

function DayColumn({ children }: { children: ReactNode }) {
  return (
    <li className="flex flex-col gap-2">
      <span className="border-b border-border pb-2 text-[0.8125rem] font-semibold text-muted-foreground">
        {children}
      </span>
      <div aria-hidden="true" className="h-[120px] rounded-md bg-muted opacity-60" />
    </li>
  );
}
