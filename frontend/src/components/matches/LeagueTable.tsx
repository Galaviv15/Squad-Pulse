import { useTranslation } from "react-i18next";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import type { LeagueTableRow } from "@/lib/matches/types";
import { cn } from "@/lib/utils";

/** Our row's look: tinted and bold, so it isn't marked by color alone. */
export const OWN_ROW_CLASSES = "bg-secondary font-semibold hover:bg-secondary";

/**
 * Our team's name in the table, with a visually hidden "(הקבוצה שלנו)": the row also carries
 * aria-current="true", and neither relies on the tint.
 */
export function TeamName({ row }: { row: LeagueTableRow }) {
  const { t } = useTranslation();
  return (
    <>
      {row.teamName}
      {row.ownTeam && <span className="sr-only"> {t("matches.ownTeamMarker")}</span>}
    </>
  );
}

/** A signed goal difference ("+5", "-3", "0"), an LTR island so the sign stays in front. */
function GoalDifference({ value }: { value: number }) {
  return <span dir="ltr">{value > 0 ? `+${value}` : String(value)}</span>;
}

/**
 * Goals for, then against: two elements in DOM order, in the page's direction, so in RTL the
 * for-number is on the right and a Hebrew reader meets it first (as on football.org.il). Never one
 * "12:8" string: a digit run is laid out left to right inside RTL text, which would put the
 * against-number on the right. Screen readers get "זכות X, חובה Y" instead of the bare numbers.
 */
function Goals({ row }: { row: LeagueTableRow }) {
  const { t } = useTranslation();
  return (
    <span className="inline-flex items-center gap-0.5">
      <span className="sr-only">
        {t("matches.table.goalsLabel", { goalsFor: row.goalsFor, goalsAgainst: row.goalsAgainst })}
      </span>
      <span aria-hidden="true" data-slot="goals-for">
        {row.goalsFor}
      </span>
      <span aria-hidden="true">:</span>
      <span aria-hidden="true" data-slot="goals-against">
        {row.goalsAgainst}
      </span>
    </span>
  );
}

/**
 * The full league table (the matches screen's table tab): position, team, played, won, drawn,
 * lost, goals (for, then against, from the start side), goal difference, points; our row highlighted. On a narrow screen it
 * scrolls sideways inside its container (the shadcn table's), never the page.
 */
export function LeagueTable({ rows }: { rows: LeagueTableRow[] }) {
  const { t } = useTranslation();
  const T = (key: string) => t(`matches.table.${key}`);

  if (rows.length === 0) {
    return <p className="text-sm text-muted-foreground">{T("empty")}</p>;
  }
  return (
    <div className="rounded-lg border border-border bg-card">
      <Table className="min-w-[640px] tabular-nums">
        <TableHeader>
          <TableRow>
            <TableHead className="w-12 text-center">{T("position")}</TableHead>
            <TableHead className="text-start">{T("team")}</TableHead>
            <TableHead className="text-center">{T("played")}</TableHead>
            <TableHead className="text-center">{T("won")}</TableHead>
            <TableHead className="text-center">{T("drawn")}</TableHead>
            <TableHead className="text-center">{T("lost")}</TableHead>
            <TableHead className="text-center">{T("goals")}</TableHead>
            <TableHead className="text-center">{T("goalDifference")}</TableHead>
            <TableHead className="text-center">{T("points")}</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {rows.map((row) => (
            // teamName, not position: the ingest makes names unique in a table (ScrapeResultRequest),
            // but positions only 1..row count, so a tie can repeat one.
            <TableRow
              key={row.teamName}
              aria-current={row.ownTeam ? "true" : undefined}
              className={cn(row.ownTeam && OWN_ROW_CLASSES)}
            >
              <TableCell className="text-center">{row.position}</TableCell>
              <TableCell className="text-start whitespace-normal">
                <TeamName row={row} />
              </TableCell>
              <TableCell className="text-center">{row.played}</TableCell>
              <TableCell className="text-center">{row.won}</TableCell>
              <TableCell className="text-center">{row.drawn}</TableCell>
              <TableCell className="text-center">{row.lost}</TableCell>
              <TableCell className="text-center">
                <Goals row={row} />
              </TableCell>
              <TableCell className="text-center">
                <GoalDifference value={row.goalDifference} />
              </TableCell>
              <TableCell className="text-center font-bold">{row.points}</TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
}
