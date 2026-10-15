import { ArrowDown, ArrowUp } from "lucide-react";
import { useTranslation } from "react-i18next";
import { GAME_EVENT_KEYS } from "@/lib/matches/labels";
import { GAME_EVENT_KINDS, type GameEvent, type GameEventKind } from "@/lib/matches/types";
import { cn } from "@/lib/utils";
import { CardMark } from "../CardMark";

/**
 * A football (the installed lucide-react has none: only Volleyball and RugbyBall), drawn like a
 * lucide icon so it sits beside them: a circle, a filled pentagon in the middle and its seams.
 */
function FootballIcon({ className }: { className?: string }) {
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
    >
      <circle cx="12" cy="12" r="10" />
      <path d="m12 7 4.76 3.45-1.82 5.6H9.06l-1.82-5.6Z" fill="currentColor" />
      <path d="M12 7V2m4.76 8.45 4.75-1.54m-6.57 7.14 2.94 4.04m-8.82-4.04-2.94 4.04m1.12-9.64L2.49 8.91" />
    </svg>
  );
}

/** A second yellow: a yellow card with a red one over it. One event, one icon. */
function SecondYellowIcon() {
  return (
    <span aria-hidden="true" className="relative inline-block h-3.5 w-4 shrink-0">
      <CardMark color="yellow" className="absolute start-0 top-0" />
      <CardMark color="red" className="absolute end-0 top-0 ring-1 ring-card" />
    </span>
  );
}

/**
 * An event's icon, decorative (aria-hidden): the text beside it (visible in the legend, sr-only in
 * a player's events) carries the meaning. The subs' arrows carry it by direction, the color is a
 * second cue only.
 */
export function GameEventIcon({ kind }: { kind: GameEventKind }) {
  switch (kind) {
    case "GOAL":
      return <FootballIcon className="size-4 shrink-0 text-foreground" />;
    case "YELLOW":
      return <CardMark color="yellow" />;
    case "SECOND_YELLOW":
      return <SecondYellowIcon />;
    case "RED":
      return <CardMark color="red" />;
    case "SUB_ON":
      return <ArrowUp aria-hidden="true" className="size-4 shrink-0 text-success" />;
    case "SUB_OFF":
      return <ArrowDown aria-hidden="true" className="size-4 shrink-0 text-danger" />;
  }
}

/**
 * One event: the icon and the minute as the site gives it ("93′", never "90+3′": an absolute
 * minute can't be split into stoppage time reliably), in an LTR island so the prime stays after
 * the digits in RTL text; an unknown minute shows the icon alone. Both are hidden from assistive
 * technology, which reads the sr-only sentence instead ("שער בדקה 23").
 */
export function GameEventItem({ event }: { event: GameEvent }) {
  const { t } = useTranslation();
  const name = t(GAME_EVENT_KEYS[event.kind]);
  return (
    <li className="inline-flex items-center gap-1">
      <GameEventIcon kind={event.kind} />
      {event.minute !== null && (
        <bdi dir="ltr" aria-hidden="true" className="text-[0.8125rem] tabular-nums">
          {`${event.minute}′`}
        </bdi>
      )}
      <span className="sr-only">
        {event.minute === null
          ? t("matches.game.eventNoMinute", { event: name })
          : t("matches.game.eventAt", { event: name, minute: event.minute })}
      </span>
    </li>
  );
}

/** Every event's icon with its name, and where the minutes come from. */
export function GameEventsLegend({ className }: { className?: string }) {
  const { t } = useTranslation();
  return (
    <div className={cn("flex flex-col gap-2 text-[0.8125rem] text-muted-foreground", className)}>
      <ul aria-label={t("matches.game.legend")} className="flex flex-wrap gap-x-4 gap-y-1.5">
        {GAME_EVENT_KINDS.map((kind) => (
          <li key={kind} className="inline-flex items-center gap-1.5">
            <GameEventIcon kind={kind} />
            {t(GAME_EVENT_KEYS[kind])}
          </li>
        ))}
      </ul>
      <p>{t("matches.game.minutesNote")}</p>
    </div>
  );
}
