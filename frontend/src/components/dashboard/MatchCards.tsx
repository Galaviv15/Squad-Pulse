import { useLayoutEffect, useRef, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { GameDetails, SideBadge } from "@/components/matches/GameRow";
import { OWN_ROW_CLASSES, TeamName } from "@/components/matches/LeagueTable";
import {
  LastUpdated,
  MappingProblemWarning,
  NoMatchData,
  QueryContent,
} from "@/components/matches/MatchDataStates";
import { Card } from "@/components/ui/card";
import { nextGame } from "@/lib/matches/games";
import { israelToday } from "@/lib/matches/israelTime";
import { useFixtures, useLeagueTable } from "@/lib/matches/queries";
import { MATCHES_PATH, MATCHES_TABLE_PATH } from "@/lib/matches/tab";
import type { Fixtures, LeagueTable } from "@/lib/matches/types";
import { cn } from "@/lib/utils";
import { centreRowInContainer } from "./scroll";

/*
 * The dashboard's two match cards: the next match and the league table, read-only (the refresh
 * control is on the matches screen only). Each reads its own query and shows its own loading and
 * error state, so one failing never affects the other or the squad row.
 */

function MatchCard({
  title,
  link,
  children,
}: {
  title: string;
  link: ReactNode;
  children: ReactNode;
}) {
  return (
    <Card className="min-h-[140px] gap-3 px-(--card-spacing)">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-base font-semibold">{title}</h2>
        {link}
      </div>
      {children}
    </Card>
  );
}

const LINK_CLASSES = "text-sm font-medium text-primary hover:underline";

/**
 * The next game (the first not played from Israel's today on): opponent, home / away, date and
 * weekday, kickoff time or "not set yet", venue; "אין משחקים קרובים" without one.
 */
export function NextMatchCard() {
  const { t } = useTranslation();
  const fixtures = useFixtures();
  return (
    <MatchCard
      title={t("dashboard.nextMatch.title")}
      link={
        <Link to={MATCHES_PATH} className={LINK_CLASSES}>
          {t("dashboard.nextMatch.toMatches")}
        </Link>
      }
    >
      <QueryContent query={fixtures} errorMessage={t("dashboard.nextMatch.loadError")}>
        {(data) => <NextMatchContent fixtures={data} />}
      </QueryContent>
    </MatchCard>
  );
}

function NextMatchContent({ fixtures }: { fixtures: Fixtures }) {
  const { t } = useTranslation();
  if (fixtures.state !== "OK") {
    return <NoMatchData state={fixtures.state} />;
  }
  const game = nextGame(fixtures.games, israelToday());
  return (
    <>
      <MappingProblemWarning problem={fixtures.mappingProblem} />
      {game === null ? (
        <p className="text-sm text-muted-foreground">{t("dashboard.nextMatch.none")}</p>
      ) : (
        <div className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-lg font-bold">{game.opponentName}</p>
            <SideBadge game={game} />
          </div>
          <GameDetails game={game} />
        </div>
      )}
      <LastUpdated at={fixtures.lastUpdatedAt} className="mt-auto" />
    </>
  );
}

/** The full league table, in a container about five rows high, scrolled to our row. */
export function LeagueTableCard() {
  const { t } = useTranslation();
  const table = useLeagueTable();
  return (
    <MatchCard
      title={t("dashboard.leagueTable.title")}
      link={
        <Link to={MATCHES_TABLE_PATH} className={LINK_CLASSES}>
          {t("dashboard.leagueTable.toTable")}
        </Link>
      }
    >
      <QueryContent query={table} errorMessage={t("dashboard.leagueTable.loadError")}>
        {(data) => <LeagueTableContent table={data} />}
      </QueryContent>
    </MatchCard>
  );
}

function LeagueTableContent({ table }: { table: LeagueTable }) {
  const { t } = useTranslation();
  if (table.state !== "OK") {
    return <NoMatchData state={table.state} />;
  }
  return (
    <>
      <MappingProblemWarning problem={table.mappingProblem} />
      {table.rows.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("matches.table.empty")}</p>
      ) : (
        <CompactTable table={table} />
      )}
      <LastUpdated at={table.lastUpdatedAt} className="mt-auto" />
    </>
  );
}

const CELL = "h-9 px-2 align-middle whitespace-nowrap";

/**
 * Every row (position, team, played, points) in a scroll container about five rows high, with a
 * sticky header inside it. On mount it's scrolled so our row is centred (top without one). Only
 * the container scrolls (its scrollTop), never the page: no scrollIntoView. A plain <table>, not
 * the shadcn Table, whose own overflow-x wrapper would become the sticky header's scroll container.
 */
function CompactTable({ table }: { table: LeagueTable }) {
  const { t } = useTranslation();
  const containerRef = useRef<HTMLDivElement>(null);
  const headerRef = useRef<HTMLTableSectionElement>(null);

  // Once, when the rows first render: a later refetch must not move what the user scrolled to.
  useLayoutEffect(() => {
    const container = containerRef.current;
    const ownRow = container?.querySelector<HTMLElement>('tr[aria-current="true"]');
    if (container && ownRow) {
      centreRowInContainer(container, ownRow, headerRef.current);
    }
  }, []);

  return (
    <div
      ref={containerRef}
      data-slot="league-table-scroll"
      className="max-h-[216px] overflow-y-auto rounded-md border border-border"
    >
      <table className="w-full text-sm tabular-nums">
        <thead ref={headerRef} className="sticky top-0 z-10 bg-muted">
          <tr className="text-[0.8125rem] text-muted-foreground">
            <th className={cn(CELL, "w-10 text-center font-medium")}>
              {t("matches.table.position")}
            </th>
            <th className={cn(CELL, "text-start font-medium")}>{t("matches.table.team")}</th>
            <th className={cn(CELL, "text-center font-medium")}>{t("matches.table.played")}</th>
            <th className={cn(CELL, "text-center font-medium")}>{t("matches.table.points")}</th>
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row) => (
            // teamName, not position: the ingest makes names unique in a table (ScrapeResultRequest),
            // but positions only 1..row count, so a tie can repeat one.
            <tr
              key={row.teamName}
              aria-current={row.ownTeam ? "true" : undefined}
              className={cn("border-t border-border", row.ownTeam && OWN_ROW_CLASSES)}
            >
              <td className={cn(CELL, "text-center")}>{row.position}</td>
              <td title={row.teamName} className={cn(CELL, "w-full max-w-0 truncate text-start")}>
                <TeamName row={row} />
              </td>
              <td className={cn(CELL, "text-center")}>{row.played}</td>
              <td className={cn(CELL, "text-center font-bold")}>{row.points}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
