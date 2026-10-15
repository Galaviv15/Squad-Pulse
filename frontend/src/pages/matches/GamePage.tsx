import { ChevronRight } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router";
import { GameHeader, GameHeaderSkeleton } from "@/components/matches/game/GameHeader";
import { GameLineup } from "@/components/matches/game/GameLineup";
import { MatchDataError } from "@/components/matches/MatchDataStates";
import { buttonVariants } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";
import { useFixtures, useGameDetails } from "@/lib/matches/queries";
import { MATCHES_PATH } from "@/lib/matches/tab";

/**
 * /app/matches/games/:detailsId (KAN-81): one played game, our side only — the header and our
 * lineup with the per-game shirt numbers and events. Read-only, VIEW_ONLY and up; no refresh
 * control, and it doesn't watch the refresh status: it's read again every time it's opened or on
 * a window refocus (its query is under MATCHES_DATA_QUERY_KEY, so a run end seen on the matches /
 * stats screens marks a cached game stale).
 *
 * Our team's name isn't in the game's details: it comes from the fixtures (usually cached, coming
 * from the matches screen). The page waits for the game and for the fixtures to settle; failed
 * fixtures, or no team name, give "הקבוצה שלנו" and no error of their own. The game's own error
 * decides the page's state: a 404 (unknown id, another club's, an earlier mapping's) is "not
 * found", never retried; anything else an error with a retry.
 */
export function GamePage() {
  const { t } = useTranslation();
  const { detailsId = "" } = useParams();
  const game = useGameDetails(detailsId);
  const fixtures = useFixtures();

  let content;
  let loading = false;
  if (game.data !== undefined && !fixtures.isPending) {
    content = (
      <>
        <GameHeader
          game={game.data}
          ownTeamName={fixtures.data?.teamName ?? t("matches.ownTeamFallback")}
        />
        <GameLineup players={game.data.players} />
      </>
    );
  } else if (game.data === undefined && game.isError && !game.isFetching) {
    content =
      game.error instanceof ApiError && game.error.status === 404 ? (
        <GameNotFound />
      ) : (
        <div role="alert">
          <MatchDataError
            message={t("matches.game.loadError")}
            onRetry={() => void game.refetch()}
          />
        </div>
      );
  } else {
    loading = true;
    content = (
      <>
        <p role="status" className="sr-only">
          {t("matches.loading")}
        </p>
        <GameHeaderSkeleton />
      </>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <div>
        <BackToMatchesLink variant="ghost" />
      </div>
      <div aria-busy={loading || undefined} className="flex flex-col gap-4">
        {content}
      </div>
    </div>
  );
}

/** To the matches screen's games (the default tab): a link, since a shared URL has no history. */
function BackToMatchesLink({ variant }: { variant: "ghost" | "outline" }) {
  const { t } = useTranslation();
  return (
    <Link to={MATCHES_PATH} className={buttonVariants({ variant, size: "sm" })}>
      {variant === "ghost" && <ChevronRight aria-hidden="true" />}
      {t("matches.game.back")}
    </Link>
  );
}

function GameNotFound() {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-border bg-card px-4 py-4 sm:px-5">
      <p className="font-medium">{t("matches.game.notFound")}</p>
      <p className="text-sm text-muted-foreground">{t("matches.game.notFoundHint")}</p>
      <BackToMatchesLink variant="outline" />
    </div>
  );
}
