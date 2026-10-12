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
 * The full league table (the matches screen's table tab): position, team, played, won, drawn,
 * lost, goals for:against, goal difference, points; our row highlighted. On a narrow screen it
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
            <TableRow
              key={row.position}
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
              {/* Goals for, then against, as read left to right (an LTR island). */}
              <TableCell className="text-center">
                <span dir="ltr">
                  {row.goalsFor}:{row.goalsAgainst}
                </span>
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
