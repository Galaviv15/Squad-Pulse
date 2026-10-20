import { useId } from "react";
import { useTranslation } from "react-i18next";
import { LastUpdated } from "../MatchDataStates";
import { GameDate, KickoffTime, ResultBadge, Scoreline, SideBadge } from "../GameRow";
import type { PlayedGameDetails } from "@/lib/matches/types";

/**
 * The game page's header card: competition and round (the section's heading: the page's <h1> is
 * the shell's), home / away and the result, the scoreline (the list's, larger), the date with its
 * weekday and the kickoff time (no venue: the game's details have none), what the numbers cover
 * and when the page was read.
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
  return (
    <section
      aria-labelledby={headingId}
      className="flex flex-col gap-4 rounded-lg border border-border bg-card px-4 py-4 sm:px-5"
    >
      <div className="flex flex-wrap items-start justify-between gap-2">
        <h2 id={headingId} className="text-base font-semibold">
          {t("matches.game.heading", { competition: game.competition, round: game.round })}
        </h2>
        <div className="flex items-center gap-2">
          <SideBadge game={game} />
          {game.result !== null && <ResultBadge result={game.result} />}
        </div>
      </div>
      <Scoreline game={game} ownTeamName={ownTeamName} size="large" />
      <div className="flex flex-wrap items-center justify-center gap-x-4 gap-y-1 text-[0.8125rem] text-muted-foreground">
        <GameDate date={game.date} />
        <KickoffTime time={game.kickoffTime} />
      </div>
      <div className="flex flex-col gap-1 border-t border-border pt-3">
        <p className="text-xs text-muted-foreground">
          {t("matches.game.scopeNote", { competition: game.competition })}
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
