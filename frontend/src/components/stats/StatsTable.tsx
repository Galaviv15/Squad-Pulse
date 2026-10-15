import { useTranslation } from "react-i18next";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { PlayerStatsRow } from "@/lib/matches/playerStats";
import { CardMark } from "@/components/matches/CardMark";
import { cn } from "@/lib/utils";
import { SquadPlayerCell, type SquadPlayerCellProps } from "./SquadPlayerCell";

/** The site's name: sticky on the start side inside the scroll box on a phone (below md). */
const STICKY_START = "max-md:sticky max-md:start-0 max-md:z-10";

const NUMBER_COLUMNS = ["games", "starts", "cameOnAsSub", "subbedOff", "minutes", "goals"] as const;

export interface StatsTableProps {
  rows: PlayerStatsRow[];
  canEdit: boolean;
  /** The rows with a link write in flight. */
  pending: ReadonlySet<string>;
  /** A row's last failed write (an i18n key). */
  errors: Readonly<Partial<Record<string, string>>>;
  /** Each row's header cell, to put focus on after a write that replaces the focused control. */
  rowHeaderRef(rowId: string, element: HTMLElement | null): void;
  onConfirmSuggestion(row: PlayerStatsRow): void;
  onPick(row: PlayerStatsRow, opener: HTMLElement | null): void;
  onUnlink(row: PlayerStatsRow): void;
}

/**
 * The scraped players, one row each, in the server's order (the site's), never re-sorted. A real
 * table: column headers, the site's name as each row's header. Inside a bordered card that scrolls
 * sideways by itself; the page never does.
 */
export function StatsTable({
  rows,
  canEdit,
  pending,
  errors,
  rowHeaderRef,
  onConfirmSuggestion,
  onPick,
  onUnlink,
}: StatsTableProps) {
  const { t } = useTranslation();
  const C = (key: string) => t(`stats.columns.${key}`);
  const head = "h-11 px-3 text-[0.8125rem] font-medium text-muted-foreground";

  return (
    <div className="rounded-lg border border-border bg-card">
      <Table aria-label={t("stats.tableLabel")} className="tabular-nums">
        <TableHeader>
          <TableRow className="bg-muted/50 hover:bg-muted/50">
            <TableHead scope="col" className={cn(head, STICKY_START, "bg-muted")}>
              {C("name")}
            </TableHead>
            <TableHead scope="col" className={head}>
              {C("squadPlayer")}
            </TableHead>
            {NUMBER_COLUMNS.map((key) => (
              <TableHead key={key} scope="col" className={cn(head, "text-center")}>
                {C(key)}
              </TableHead>
            ))}
            <CardHead
              color="yellow"
              label={C("yellowLeagueAndCup")}
              spoken={t("stats.columns.yellowLabel", { competition: C("yellowLeagueAndCup") })}
            />
            <CardHead
              color="yellow"
              label={C("yellowToto")}
              spoken={t("stats.columns.yellowLabel", { competition: C("yellowToto") })}
            />
            <CardHead color="red" label={C("red")} spoken={C("redLabel")} />
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            <TableRow key={row.id} className="h-[52px]">
              <th
                scope="row"
                ref={(element) => rowHeaderRef(row.id, element)}
                tabIndex={-1}
                className={cn(
                  "bg-card px-3 text-start font-medium whitespace-nowrap outline-none",
                  STICKY_START,
                )}
              >
                {row.name}
              </th>
              <TableCell className="px-3">
                <SquadPlayerCell
                  {...cellProps(row, {
                    canEdit,
                    pending,
                    errors,
                    onConfirmSuggestion,
                    onPick,
                    onUnlink,
                  })}
                />
              </TableCell>
              <Num>{row.games}</Num>
              <Num>{row.starts}</Num>
              <Num>{row.cameOnAsSub}</Num>
              <Num>{row.subbedOff}</Num>
              <Num>{row.minutes}</Num>
              <Num className="font-bold">{row.goals}</Num>
              <Num>{row.yellowCardsLeagueAndCup}</Num>
              <Num>{row.yellowCardsToto}</Num>
              <Num>{row.redCards}</Num>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}

function cellProps(
  row: PlayerStatsRow,
  props: Omit<StatsTableProps, "rows" | "rowHeaderRef">,
): SquadPlayerCellProps {
  return {
    row,
    canEdit: props.canEdit,
    pending: props.pending.has(row.id),
    error: props.errors[row.id] ?? null,
    onConfirmSuggestion: () => props.onConfirmSuggestion(row),
    onPick: (opener) => props.onPick(row, opener),
    onUnlink: () => props.onUnlink(row),
  };
}

/**
 * A card column's header: the mark and the short visible text ("טוטו"); a screen reader gets the
 * full name instead ("כרטיסים צהובים, טוטו"), so it never hears "טוטו" alone.
 */
function CardHead({
  color,
  label,
  spoken,
}: {
  color: "yellow" | "red";
  label: string;
  spoken: string;
}) {
  return (
    <TableHead
      scope="col"
      className="h-11 px-3 text-center text-[0.8125rem] font-medium text-muted-foreground"
    >
      <span className="inline-flex items-center gap-1.5">
        <CardMark color={color} />
        <span aria-hidden="true">{label}</span>
        <span className="sr-only">{spoken}</span>
      </span>
    </TableHead>
  );
}

function Num({ children, className }: { children: number; className?: string }) {
  return <TableCell className={cn("px-3 text-center", className)}>{children}</TableCell>;
}
