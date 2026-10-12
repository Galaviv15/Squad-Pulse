import { Clock, MapPin } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import { formatIsoDate } from "@/lib/squad/dates";
import type { GameStatus } from "@/lib/matches/games";
import { weekdayOf } from "@/lib/matches/israelTime";
import { RESULT_KEYS, SIDE_KEYS, WEEKDAY_KEYS } from "@/lib/matches/labels";
import type { Game, Result } from "@/lib/matches/types";
import { cn } from "@/lib/utils";

/**
 * "יום שבת, 05.09.2026": the game's Israel-local date with its weekday, both from the date's
 * parts (the date is already Israel's: no zone conversion).
 */
function GameDate({ date }: { date: string }) {
  const { t } = useTranslation();
  const weekday = weekdayOf(date);
  const formatted = formatIsoDate(date) ?? date;
  return (
    <span className="font-medium text-foreground">
      {weekday === null
        ? formatted
        : t("matches.dateWithWeekday", { weekday: t(WEEKDAY_KEYS[weekday]), date: formatted })}
    </span>
  );
}

/** The game's date, kickoff time (or "not set yet") and venue (when the site shows one). */
export function GameDetails({ game, className }: { game: Game; className?: string }) {
  const { t } = useTranslation();
  return (
    <div
      className={cn(
        "flex flex-wrap items-center gap-x-4 gap-y-1 text-[0.8125rem] text-muted-foreground",
        className,
      )}
    >
      <GameDate date={game.date} />
      <span className="flex items-center gap-1">
        <Clock aria-hidden="true" className="size-3.5 shrink-0" />
        {game.kickoffTime === null ? (
          t("matches.timeNotSet")
        ) : (
          <span className="tabular-nums">{game.kickoffTime}</span>
        )}
      </span>
      {game.venue !== null && (
        <span className="flex items-center gap-1">
          <MapPin aria-hidden="true" className="size-3.5 shrink-0" />
          <span className="sr-only">{t("matches.venueLabel")}</span>
          {game.venue}
        </span>
      )}
    </div>
  );
}

const RESULT_VARIANTS: Record<Result, "success" | "muted" | "danger"> = {
  WIN: "success",
  DRAW: "muted",
  LOSS: "danger",
};

/** Home or away, from our team's side. */
export function SideBadge({ game }: { game: Game }) {
  const { t } = useTranslation();
  return <Badge variant="outline">{t(SIDE_KEYS[game.ownSide])}</Badge>;
}

/**
 * The two teams and, once played, the two scores: home team · home score · separator · away score
 * · away team, in this DOM order, each score its own element beside its team. Never one "3–1"
 * string: a run of digits is laid out left to right inside RTL text, which would put the home
 * score on the left while the home team's name is on the right. As a grid in DOM order, RTL puts
 * the home side on the right, its score next to it. Our team is in bold.
 */
export function Scoreline({ game, ownTeamName }: { game: Game; ownTeamName: string }) {
  const { t } = useTranslation();
  const home = game.ownSide === "HOME";
  const sides = [
    {
      name: home ? ownTeamName : game.opponentName,
      own: home,
      score: home ? game.ownScore : game.opponentScore,
    },
    {
      name: home ? game.opponentName : ownTeamName,
      own: !home,
      score: home ? game.opponentScore : game.ownScore,
    },
  ];
  const played = game.result !== null && sides.every(({ score }) => score !== null);

  return (
    <div
      data-slot="scoreline"
      className="grid grid-cols-[minmax(0,1fr)_auto_auto_auto_minmax(0,1fr)] items-center gap-x-2 text-sm"
    >
      <span
        data-slot="home-team"
        className={cn("min-w-0 text-end break-words", sides[0].own && "font-semibold")}
      >
        {sides[0].name}
      </span>
      {played ? (
        <>
          <span data-slot="home-score" className="text-base font-bold tabular-nums">
            {sides[0].score}
          </span>
          <span aria-hidden="true" className="text-muted-foreground">
            –
          </span>
          <span data-slot="away-score" className="text-base font-bold tabular-nums">
            {sides[1].score}
          </span>
        </>
      ) : (
        <span className="col-span-3 px-1 text-[0.8125rem] text-muted-foreground">
          {t("matches.versus")}
        </span>
      )}
      <span
        data-slot="away-team"
        className={cn("min-w-0 text-start break-words", sides[1].own && "font-semibold")}
      >
        {sides[1].name}
      </span>
    </div>
  );
}

/**
 * One game of the season. Played: the score and a win / draw / loss badge. Not played: upcoming
 * from today on, the next one highlighted (a "המשחק הבא" badge, not color alone); before today
 * "אין תוצאה" (postponed, or the site isn't updated yet), never a made-up score.
 */
export function GameRow({
  game,
  status,
  isNext,
  ownTeamName,
}: {
  game: Game;
  status: GameStatus;
  isNext: boolean;
  ownTeamName: string;
}) {
  const { t } = useTranslation();
  return (
    <li
      data-next={isNext || undefined}
      className={cn(
        "flex flex-col gap-3 rounded-lg border bg-card px-4 py-3.5",
        isNext ? "border-primary bg-secondary" : "border-border",
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <GameDetails game={game} />
        <div className="flex items-center gap-2">
          {isNext && <Badge>{t("matches.next")}</Badge>}
          <SideBadge game={game} />
          {status === "played" && game.result !== null && (
            <Badge variant={RESULT_VARIANTS[game.result]}>{t(RESULT_KEYS[game.result])}</Badge>
          )}
          {status === "noResult" && <Badge variant="muted">{t("matches.noResult")}</Badge>}
        </div>
      </div>
      <Scoreline game={game} ownTeamName={ownTeamName} />
    </li>
  );
}
