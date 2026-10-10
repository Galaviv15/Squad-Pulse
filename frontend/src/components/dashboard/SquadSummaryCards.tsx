import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { LINE_KEYS } from "@/lib/squad/labels";
import { SQUAD_PATH } from "@/lib/squad/paths";
import { formatAverageAge, useSquadSummary } from "@/lib/squad/summary";
import { LINES, type SquadSummary } from "@/lib/squad/types";
import { cn } from "@/lib/utils";
import { LINE_COLORS } from "./donut";
import { LineDonut } from "./LineDonut";

/**
 * The dashboard's squad row, from GET /squad/summary: the active-players and average-age tiles and
 * the by-line card (donut + legend). Until the first answer, one loading message in their place;
 * a failed first load, one error message with a retry. A failed background refetch keeps the data
 * shown (TanStack Query keeps `data`), so only "no data yet" ever shows a state.
 *
 * The columns follow the row's own width (a container query), not the viewport's, since the
 * sidebar takes part of it: one column, two from 500px (the tiles side by side, the by-line card
 * under them across both), four from 1020px (all in one row, the by-line card across two). Never
 * three, which would leave a hole beside the two-column card; and the card only spans two where
 * there are two, so it never adds a column of its own.
 */
export function SquadSummaryCards() {
  const { t } = useTranslation();
  const summary = useSquadSummary();
  const { data } = summary;

  let content: ReactNode;
  if (data === undefined) {
    content =
      summary.isError && !summary.isFetching ? (
        <StatePanel>
          <p>{t("dashboard.loadError")}</p>
          <Button variant="outline" size="sm" onClick={() => void summary.refetch()}>
            {t("squad.retry")}
          </Button>
        </StatePanel>
      ) : (
        <StatePanel>
          <p role="status">{t("auth.loading")}</p>
        </StatePanel>
      );
  } else {
    content = (
      <div className="grid grid-cols-1 gap-5 @min-[500px]:grid-cols-2 @min-[1020px]:grid-cols-4">
        <Tile title={t("dashboard.activePlayers")}>
          <KpiNumber>{data.playerCount}</KpiNumber>
          {/* The bare squad, never the last one seen: this tile counts the active players, which
              a remembered view (e.g. the released players) would contradict. */}
          <Link to={SQUAD_PATH} className="w-fit text-sm font-medium text-primary hover:underline">
            {t("dashboard.toSquadTable")}
          </Link>
        </Tile>
        <Tile title={t("dashboard.averageAge")}>
          <KpiNumber>{formatAverageAge(data.averageAge)}</KpiNumber>
          <p className="text-[0.8125rem] text-muted-foreground">
            {t("dashboard.activePlayersOnly")}
          </p>
        </Tile>
        <LinesCard summary={data} className="@min-[500px]:col-span-2" />
      </div>
    );
  }

  return <div className="@container">{content}</div>;
}

function Tile({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card className="gap-3 px-(--card-spacing)">
      <h2 className="text-sm font-medium text-muted-foreground">{title}</h2>
      {children}
    </Card>
  );
}

function KpiNumber({ children }: { children: ReactNode }) {
  return <p className="text-[2.5rem] leading-none font-bold tabular-nums">{children}</p>;
}

/**
 * The by-line card: the donut and its legend (color, line name, count; no percentages), in LINES
 * order. The legend wraps under the donut when the card is too narrow for both side by side.
 */
function LinesCard({ summary, className }: { summary: SquadSummary; className?: string }) {
  const { t } = useTranslation();

  return (
    <Card className={cn("gap-4 px-(--card-spacing)", className)}>
      <h2 className="text-base font-semibold">{t("dashboard.lines.title")}</h2>
      <div className="flex flex-wrap items-center gap-6">
        <LineDonut lines={summary.lines} total={summary.playerCount} />
        <ul className="grid min-w-0 grow basis-[220px] grid-cols-2 gap-x-6 gap-y-3">
          {LINES.map((line) => (
            <li key={line} className="flex items-center gap-2 text-sm">
              <span
                aria-hidden="true"
                className={cn("size-3 shrink-0 rounded-[3px]", LINE_COLORS[line].swatch)}
              />
              <span className="font-medium">{t(LINE_KEYS[line])}</span>
              <span className="ms-auto font-semibold tabular-nums">{summary.lines[line]}</span>
            </li>
          ))}
        </ul>
      </div>
    </Card>
  );
}

function StatePanel({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-[220px] flex-col items-center justify-center gap-3 rounded-lg border border-border bg-card px-4 py-12 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
