import { LeagueTableCard, NextMatchCard } from "@/components/dashboard/MatchCards";
import {
  NextTrainingPlaceholder,
  WeeklySchedulePlaceholder,
} from "@/components/dashboard/ComingSoon";
import { SquadSummaryCards } from "@/components/dashboard/SquadSummaryCards";

/**
 * /app: the dashboard. The squad row (KPI tiles and the by-line donut, from GET /squad/summary),
 * then a row of the next training (a "coming soon" placeholder), the next match and the league
 * table (KAN-69, from GET /matches/fixtures and /matches/league-table), then the weekly-schedule
 * placeholder. Each card shows its own loading and error state, so one failing request never
 * affects the others; the placeholders need no data and show at once. Its title comes from the
 * route (the shell's <h1>); the cards' titles are <h2>s.
 */
export function DashboardPage() {
  return (
    <div className="flex flex-col gap-5">
      <SquadSummaryCards />
      <div className="grid grid-cols-[repeat(auto-fit,minmax(min(260px,100%),1fr))] gap-5">
        <NextTrainingPlaceholder />
        <NextMatchCard />
        <LeagueTableCard />
      </div>
      <WeeklySchedulePlaceholder />
    </div>
  );
}
