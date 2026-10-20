import { useId } from "react";
import { useTranslation } from "react-i18next";
import { LastUpdated } from "../MatchDataStates";
import {
  DecidedByLine,
  GameDate,
  KickoffTime,
  ResultBadge,
  Scoreline,
  SideBadge,
} from "../GameRow";
import { competitionText } from "@/lib/matches/labels";
import type { PlayedGameDetails } from "@/lib/matches/types";

/**
 * The game page's header card: competition and round (the section's heading: the page's <h1> is
 * the shell's), home / away and the result, the scoreline (the list's, larger) with a cup game's
 * later stages, the date with its weekday and the kickoff time (no venue: the game's details have
 * none), what the numbers cover and when the page was read.
 *
 * A cup game (`competitionType` set, KAN-86) is named as on the matches screen, our label and the
 * cup list's round ("גביע המדינה · סיבוב ו"), never the page's "מחזור N", which is the site's own
 * number and not the cup's round. Anything else (null, or LEAGUE): the page's heading and round,
 * as before. The scope note names the same competition.
 */
export function GameHeader({
  game,
  ownTeamName,
}: {
  game: PlayedGameDetails;
  ownTeamName: string;
}) {
  const { t } = useTranslation();
  const headingId = useId();
  const cupType = game.competitionType === "LEAGUE" ? null : game.competitionType;
  const competition = cupType === null ? game.competition : competitionText(t, cupType, null);
  const heading =
    cupType === null
      ? t("matches.game.heading", { competition, round: game.round })
      : competitionText(t, cupType, game.cupRound);
  return (
    <section
      aria-labelledby={headingId}
      className="flex flex-col gap-4 rounded-lg border border-border bg-card px-4 py-4 sm:px-5"
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h2 id={headingId} className="text-base font-semibold">
          {heading}
        </h2>
        <div className="flex items-center gap-2">
          <SideBadge game={game} />
          {game.result !== null && <ResultBadge result={game.result} />}
        </div>
      </div>
      <div className="flex flex-col gap-1.5">
        <Scoreline game={game} ownTeamName={ownTeamName} size="large" />
        <DecidedByLine game={game} />
      </div>
      <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-1 text-[0.8125rem] text-muted-foreground">
        <GameDate date={game.date} />
        <KickoffTime time={game.kickoffTime} />
      </div>
      <div className="flex flex-col gap-1 border-t border-border pt-3">
        <p className="text-xs text-muted-foreground">
          {t("matches.game.scopeNote", { competition })}
        </p>
        <LastUpdated at={game.lastUpdatedAt} />
      </div>
    </section>
  );
}

/** The header's place while loading: the same frame, so nothing jumps when the data comes. */
export function GameHeaderSkeleton() {
  return (
    <div
      aria-hidden="true"
      className="flex flex-col gap-4 rounded-lg border border-border bg-card px-4 py-4 sm:px-5"
    >
      <div className="h-5 w-48 animate-pulse rounded bg-muted" />
      <div className="mx-auto h-8 w-full max-w-sm animate-pulse rounded bg-muted" />
      <div className="mx-auto h-4 w-40 animate-pulse rounded bg-muted" />
      <div className="h-10 animate-pulse rounded bg-muted" />
    </div>
  );
}
