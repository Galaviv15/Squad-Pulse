import { useTranslation } from "react-i18next";
import { gameStatus, nextGame } from "@/lib/matches/games";
import { israelToday } from "@/lib/matches/israelTime";
import type { Fixtures } from "@/lib/matches/types";
import { GameRow } from "./GameRow";

/**
 * The season's games, in the server's order (by date, then kickoff time), each against Israel's
 * today: played, upcoming (the next one highlighted) or without a result.
 */
export function GamesList({ fixtures }: { fixtures: Fixtures }) {
  const { t } = useTranslation();
  const today = israelToday();
  const next = nextGame(fixtures.games, today);
  const ownTeamName = fixtures.teamName ?? t("matches.ownTeamFallback");

  if (fixtures.games.length === 0) {
    return <p className="text-sm text-muted-foreground">{t("matches.noGames")}</p>;
  }
  return (
    <ol aria-label={t("matches.gamesLabel")} className="flex flex-col gap-3">
      {fixtures.games.map((game, index) => (
        // No ids in the response: the server's order is stable for one answer.
        <GameRow
          key={index}
          game={game}
          status={gameStatus(game, today)}
          isNext={game === next}
          ownTeamName={ownTeamName}
        />
      ))}
    </ol>
  );
}
