import { useTranslation } from "react-i18next";
import { GamesList } from "@/components/matches/GamesList";
import { LeagueTable } from "@/components/matches/LeagueTable";
import {
  LastUpdated,
  MappingProblemWarning,
  NoMatchData,
  QueryContent,
} from "@/components/matches/MatchDataStates";
import { MatchesTabs } from "@/components/matches/MatchesTabs";
import { RefreshControl } from "@/components/matches/RefreshControl";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { useFixtures, useLeagueTable } from "@/lib/matches/queries";
import { useMatchesTab } from "@/lib/matches/tab";
import type { Fixtures, LeagueTable as LeagueTableData } from "@/lib/matches/types";

/**
 * /app/matches (KAN-69): the club's league data, scraped from football.org.il. Two tabs, the
 * selected one in the URL (`?tab=table`): the season's games (default) and the league table, each
 * with its own "last updated" line. The header holds the refresh control, for EDIT_FULL and up
 * only: below that it isn't rendered at all, so GET /matches/refresh is never requested.
 *
 * Both reads are active whichever tab shows: switching tabs is instant, and a refresh run's end
 * (which invalidates both) refetches both at once.
 */
export function MatchesPage() {
  const { permissionLevel } = useCurrentUser();
  const { tab, setTab } = useMatchesTab();
  const fixtures = useFixtures();
  const table = useLeagueTable();

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <MatchesTabs value={tab} onChange={setTab} />
        {hasPermission(permissionLevel, "EDIT_FULL") && <RefreshControl />}
      </div>
      {tab === "games" ? <GamesTab fixtures={fixtures} /> : <TableTab table={table} />}
    </div>
  );
}

function GamesTab({ fixtures }: { fixtures: ReturnType<typeof useFixtures> }) {
  const { t } = useTranslation();
  return (
    <QueryContent query={fixtures} errorMessage={t("dashboard.nextMatch.loadError")}>
      {(data: Fixtures) =>
        data.state !== "OK" ? (
          <NoMatchData state={data.state} />
        ) : (
          <div className="flex flex-col gap-4">
            <MappingProblemWarning problem={data.mappingProblem} />
            <LastUpdated at={data.lastUpdatedAt} />
            <GamesList fixtures={data} />
          </div>
        )
      }
    </QueryContent>
  );
}

function TableTab({ table }: { table: ReturnType<typeof useLeagueTable> }) {
  const { t } = useTranslation();
  return (
    <QueryContent query={table} errorMessage={t("dashboard.leagueTable.loadError")}>
      {(data: LeagueTableData) =>
        data.state !== "OK" ? (
          <NoMatchData state={data.state} />
        ) : (
          <div className="flex flex-col gap-4">
            <MappingProblemWarning problem={data.mappingProblem} />
            <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
              {data.round !== null && (
                <h2 className="text-lg font-semibold">
                  {t("matches.round", { round: data.round })}
                </h2>
              )}
              <LastUpdated at={data.lastUpdatedAt} />
            </div>
            <LeagueTable rows={data.rows} />
          </div>
        )
      }
    </QueryContent>
  );
}
