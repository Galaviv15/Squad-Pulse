import { ComingSoonCards, WeeklySchedulePlaceholder } from "@/components/dashboard/ComingSoon";
import { SquadSummaryCards } from "@/components/dashboard/SquadSummaryCards";

/**
 * /app: the dashboard. The squad row (KPI tiles and the by-line donut, from GET /squad/summary),
 * then the "coming soon" placeholders, which need no data and show at once, whatever the summary's
 * state. Its title comes from the route (the shell's <h1>); the cards' titles are <h2>s.
 */
export function DashboardPage() {
  return (
    <div className="flex flex-col gap-5">
      <SquadSummaryCards />
      <ComingSoonCards />
      <WeeklySchedulePlaceholder />
    </div>
  );
}
