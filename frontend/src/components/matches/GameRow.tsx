import { ChevronLeft, Clock, MapPin } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { Badge } from "@/components/ui/badge";
import { buttonVariants } from "@/components/ui/button";
import { formatIsoDate } from "@/lib/squad/dates";
import type { GameStatus } from "@/lib/matches/games";
import { weekdayOf } from "@/lib/matches/israelTime";
import {
  competitionText,
  DECIDED_BY_KEYS,
  DETAILS_STATE_TEXT_KEYS,
  RESULT_KEYS,
  SIDE_KEYS,
  WEEKDAY_KEYS,
} from "@/lib/matches/labels";
import { gamePath } from "@/lib/matches/paths";
import type { DecidedBy, Game, Result, Score } from "@/lib/matches/types";
import { cn } from "@/lib/utils";

/**
 * "יום שבת, 05.09.2026": the game's Israel-local date with its weekday, both from the date's
 * parts (the date is already Israel's: no zone conversion).
 */
export function GameDate({ date }: { date: string }) {
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

/** The kickoff time (Israel-local HH:mm), or "not set yet". */
export function KickoffTime({ time }: { time: string | null }) {
  const { t } = useTranslation();
  return (
    <span className="flex items-center gap-1">
      <Clock aria-hidden="true" className="size-3.5 shrink-0" />
      {time === null ? t("matches.timeNotSet") : <span className="tabular-nums">{time}</span>}
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
      <KickoffTime time={game.kickoffTime} />
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
export function SideBadge({ game }: { game: Pick<Game, "ownSide"> }) {
  const { t } = useTranslation();
  return <Badge variant="outline">{t(SIDE_KEYS[game.ownSide])}</Badge>;
}

/** The competition (and a cup's round) as a text badge, on every row, league too. */
export function CompetitionBadge({ game }: { game: Pick<Game, "competition" | "round"> }) {
  const { t } = useTranslation();
  return (
    <Badge
      variant="outline"
      data-slot="competition"
      // A cup round is free text and may be long: it wraps at phone width, never overflows.
      className="h-auto max-w-full text-start whitespace-normal"
    >
      {competitionText(t, game.competition, game.round)}
    </Badge>
  );
}

/** Win / draw / loss, from our team's point of view. */
export function ResultBadge({ result }: { result: Result }) {
  const { t } = useTranslation();
  return <Badge variant={RESULT_VARIANTS[result]}>{t(RESULT_KEYS[result])}</Badge>;
}

/** What Scoreline reads: a fixture's game and a played game's details (KAN-81) both have it. */
export type ScorelineGame = Pick<Game, "ownSide" | "opponentName" | "ownScore" | "opponentScore">;

/**
 * Two scores, home first, each its own element with a separator between: the piece Scoreline and
 * DecidedByLine share. Laid out by its parent (a grid or a flex row), so RTL puts the home score
 * on the right, the home side's; never one "3–1" string (see Scoreline). `slotPrefix` keeps the
 * secondary line's slots apart from the main score's.
 */
function ScorePair({
  home,
  away,
  className,
  slotPrefix = "",
}: {
  home: number;
  away: number;
  className?: string;
  slotPrefix?: string;
}) {
  return (
    <>
      <span data-slot={`${slotPrefix}home-score`} className={className}>
        {home}
      </span>
      <span aria-hidden="true" className="text-muted-foreground">
        –
      </span>
      <span data-slot={`${slotPrefix}away-score`} className={className}>
        {away}
      </span>
    </>
  );
}

/**
 * The two teams and, once played, the two scores: home team · home score · separator · away score
 * · away team, in this DOM order, each score its own element beside its team. Never one "3–1"
 * string: a run of digits is laid out left to right inside RTL text, which would put the home
 * score on the left while the home team's name is on the right. As a grid in DOM order, RTL puts
 * the home side on the right, its score next to it. Our team is in bold. `large`: the game page's
 * header (KAN-81). Played = both scores set (the 90-minute score), never read from `result`: a cup
 * game's can be null with scores (KAN-86).
 */
export function Scoreline({
  game,
  ownTeamName,
  size = "default",
}: {
  game: ScorelineGame;
  ownTeamName: string;
  size?: "default" | "large";
}) {
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
  const [homeScore, awayScore] = [sides[0].score, sides[1].score];
  const scoreSize = size === "large" ? "text-2xl sm:text-3xl" : "text-base";

  return (
    <div
      data-slot="scoreline"
      className={cn(
        "grid grid-cols-[minmax(0,1fr)_auto_auto_auto_minmax(0,1fr)] items-center",
        size === "large" ? "gap-x-3 text-base sm:text-lg" : "gap-x-2 text-sm",
      )}
    >
      <span
        data-slot="home-team"
        className={cn("min-w-0 text-end break-words", sides[0].own && "font-semibold")}
      >
        {sides[0].name}
      </span>
      {homeScore !== null && awayScore !== null ? (
        <ScorePair
          home={homeScore}
          away={awayScore}
          className={cn("font-bold tabular-nums", scoreSize)}
        />
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

/** What DecidedByLine reads: a fixture's game and a played game's details both have it. */
export type DecidedByGame = Pick<Game, "ownSide" | "extraTime" | "penalties" | "decidedBy">;

/**
 * A cup game decided after the 90 minutes (KAN-86): under the scoreline, which stays the 90-minute
 * score, each later stage present with its label and score: "אחרי הארכה 3–2", or with a shootout
 * the extra time (when there was any) then "בפנדלים 1–3" (the tally only). Home first, each score
 * its own element (ScorePair), so it lines up with the scoreline's sides. Nothing when `decidedBy`
 * is null.
 */
export function DecidedByLine({ game }: { game: DecidedByGame }) {
  const { t } = useTranslation();
  if (game.decidedBy === null) {
    return null;
  }
  const home = game.ownSide === "HOME";
  const stages = (
    [
      ["EXTRA_TIME", game.extraTime],
      ["PENALTIES", game.penalties],
    ] satisfies [DecidedBy, Score | null][]
  ).filter((stage): stage is [DecidedBy, Score] => stage[1] !== null);
  if (stages.length === 0) {
    return null;
  }
  return (
    <p
      data-slot="decided-by"
      className="flex flex-wrap items-center justify-center gap-x-4 gap-y-1 text-xs text-muted-foreground"
    >
      {stages.map(([stage, score]) => (
        <span
          key={stage}
          data-slot={stage === "EXTRA_TIME" ? "extra-time" : "penalties"}
          className="inline-flex items-center gap-1"
        >
          <span>{t(DECIDED_BY_KEYS[stage])}</span>
          <ScorePair
            home={home ? score.ownScore : score.opponentScore}
            away={home ? score.opponentScore : score.ownScore}
            className="font-semibold text-foreground tabular-nums"
            slotPrefix="stage-"
          />
        </span>
      ))}
    </p>
  );
}

/**
 * One game of the season, league or cup, its competition (and a cup's round) as a badge. Played:
 * the 90-minute score, a cup game's later stages under it, and a win / draw / loss badge (who went
 * through; none when the backend has no result for a played game). Not played: upcoming
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
        <div className="flex flex-wrap items-center gap-2">
          {isNext && <Badge>{t("matches.next")}</Badge>}
          <CompetitionBadge game={game} />
          <SideBadge game={game} />
          {status === "played" && game.result !== null && <ResultBadge result={game.result} />}
          {status === "noResult" && <Badge variant="muted">{t("matches.noResult")}</Badge>}
        </div>
      </div>
      <div className="flex flex-col gap-1">
        <Scoreline game={game} ownTeamName={ownTeamName} />
        <DecidedByLine game={game} />
      </div>
      <DetailsFooter game={game} />
    </li>
  );
}

/**
 * The row's footer (KAN-81, the matches screen only), from `detailsState` alone: AVAILABLE → a
 * link to the game's page, styled as a button; its name is the visible "פרטי משחק" followed by
 * the opponent and the date in sr-only text (a season has many such links, and the name must start
 * with the visible label, WCAG 2.5.3, so no aria-label); PENDING / UNAVAILABLE → a muted sentence; a game not played (null) → nothing. AVAILABLE
 * without a detailsId can't come from the backend: nothing, never a broken link.
 */
function DetailsFooter({ game }: { game: Game }) {
  const { t } = useTranslation();
  const { detailsState, detailsId } = game;
  if (detailsState === null) {
    return null;
  }
  if (detailsState !== "AVAILABLE") {
    return (
      <RowFooter>
        <p className="text-[0.8125rem] text-muted-foreground">
          {t(DETAILS_STATE_TEXT_KEYS[detailsState])}
        </p>
      </RowFooter>
    );
  }
  if (detailsId === null) {
    return null;
  }
  return (
    <RowFooter>
      <Link to={gamePath(detailsId)} className={buttonVariants({ variant: "outline", size: "sm" })}>
        {t("matches.details.action")}
        <span className="sr-only">
          {t("matches.details.actionContext", {
            opponent: game.opponentName,
            date: formatIsoDate(game.date) ?? game.date,
          })}
        </span>
        <ChevronLeft aria-hidden="true" />
      </Link>
    </RowFooter>
  );
}

/** On the inline-end side, under a divider. */
function RowFooter({ children }: { children: ReactNode }) {
  return <div className="flex justify-end border-t border-border pt-3">{children}</div>;
}
