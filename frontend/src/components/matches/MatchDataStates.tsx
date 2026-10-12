import { CircleAlert, TriangleAlert } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { formatInstant } from "@/lib/matches/israelTime";
import type { MappingProblem, MatchDataState } from "@/lib/matches/types";
import { cn } from "@/lib/utils";

/*
 * The pieces every view of the scraped data shares (the dashboard cards, the matches screen's
 * tabs): the "last updated" line, the not-mapped / no-data texts, the mapping-problem warning and
 * the loading / error states.
 */

/**
 * "עודכן לאחרונה: dd.MM.yyyy HH:mm" (Israel time): every scraped value shows when the site was
 * read (docs/spec.md section 07). The data's own time, not the refresh run's: a failed run keeps
 * the old data with its old time, and the league table's time may come from another club's scrape
 * of the same league. Nothing without one.
 */
export function LastUpdated({ at, className }: { at: string | null; className?: string }) {
  const { t } = useTranslation();
  if (at === null) {
    return null;
  }
  return (
    <p className={cn("text-[0.8125rem] text-muted-foreground", className)}>
      {t("matches.lastUpdated", { time: formatInstant(at) })}
    </p>
  );
}

/** The text for a read without data (NOT_MAPPED, NO_DATA); null for OK. */
export function NoMatchData({ state }: { state: MatchDataState }) {
  const { t } = useTranslation();
  if (state === "OK") {
    return null;
  }
  return (
    <p className="text-sm text-muted-foreground">
      {t(state === "NOT_MAPPED" ? "matches.notMapped" : "matches.noData")}
    </p>
  );
}

/**
 * The warning for a mapping the data shows is probably wrong (TEAM_NOT_IN_STANDINGS), to every
 * user: the data still shows below it.
 */
export function MappingProblemWarning({ problem }: { problem: MappingProblem | null }) {
  const { t } = useTranslation();
  if (problem === null) {
    return null;
  }
  return (
    <div className="flex items-start gap-2 rounded-md bg-danger-muted px-3.5 py-3 text-[0.8125rem] font-medium text-danger">
      <TriangleAlert aria-hidden="true" className="size-[18px] shrink-0" />
      <p>{t("matches.mappingProblem")}</p>
    </div>
  );
}

/** A read's loading message. */
export function MatchDataLoading() {
  const { t } = useTranslation();
  return (
    <p role="status" className="text-sm text-muted-foreground">
      {t("matches.loading")}
    </p>
  );
}

/** A failed read (no data yet): the message and a retry. */
export function MatchDataError({ message, onRetry }: { message: string; onRetry: () => void }) {
  const { t } = useTranslation();
  return (
    <div className="flex flex-col items-start gap-3 text-sm text-muted-foreground">
      <p className="flex items-center gap-2">
        <CircleAlert aria-hidden="true" className="size-4 shrink-0 text-danger" />
        {message}
      </p>
      <Button variant="outline" size="sm" onClick={onRetry}>
        {t("matches.retry")}
      </Button>
    </div>
  );
}

interface DataQuery<T> {
  data: T | undefined;
  isError: boolean;
  isFetching: boolean;
  refetch: () => unknown;
}

/**
 * Renders a read's states, like the squad summary: loading until the first answer, a failed
 * first load as an error with a retry, and the data otherwise — kept on a failed background
 * refetch (TanStack keeps `data`), so only "no data yet" ever shows a state.
 */
export function QueryContent<T>({
  query,
  errorMessage,
  children,
}: {
  query: DataQuery<T>;
  errorMessage: string;
  children: (data: T) => ReactNode;
}) {
  if (query.data !== undefined) {
    return children(query.data);
  }
  if (query.isError && !query.isFetching) {
    return <MatchDataError message={errorMessage} onRetry={() => void query.refetch()} />;
  }
  return <MatchDataLoading />;
}
