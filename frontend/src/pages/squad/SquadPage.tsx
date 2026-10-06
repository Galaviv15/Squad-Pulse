import { Plus } from "lucide-react";
import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { SquadFilterBar } from "@/components/squad/SquadFilterBar";
import { SquadTable } from "@/components/squad/SquadTable";
import { StatusControl } from "@/components/squad/StatusControl";
import { Button, buttonVariants } from "@/components/ui/button";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { hasFilterBarFilters } from "@/lib/squad/filters";
import { EMPTY_SQUAD_KEYS } from "@/lib/squad/labels";
import { NEW_PLAYER_PATH } from "@/lib/squad/paths";
import { useSquadPlayers } from "@/lib/squad/players";
import { useSquadFilters } from "@/lib/squad/useSquadFilters";
import { cn } from "@/lib/utils";

/**
 * /app/squad: the squad table. The status control and the "add player" link (EDIT_FULL and up),
 * the filter bar, the count, and the table. The filters live in the URL (useSquadFilters); the
 * list comes from useSquadPlayers, in the server's order. Its title comes from the route.
 */
export function SquadPage() {
  const { t } = useTranslation();
  const { permissionLevel } = useCurrentUser();
  const { filters, setFilters, clearFilters } = useSquadFilters();
  const players = useSquadPlayers(filters);
  const [clearCount, setClearCount] = useState(0);

  function clear() {
    clearFilters();
    setClearCount((count) => count + 1);
  }

  const { data } = players;
  let content: ReactNode;
  if (data === undefined) {
    content =
      players.isError && !players.isFetching ? (
        <TableMessage>
          <p>{t("squad.loadError")}</p>
          <Button variant="outline" size="sm" onClick={() => void players.refetch()}>
            {t("squad.retry")}
          </Button>
        </TableMessage>
      ) : (
        <TableMessage>
          <p role="status">{t("squad.loading")}</p>
        </TableMessage>
      );
  } else if (data.length === 0) {
    const filtered = hasFilterBarFilters(filters);
    content = (
      <TableMessage>
        <p>{t(filtered ? "squad.empty.filtered" : EMPTY_SQUAD_KEYS[filters.status])}</p>
        {filtered && (
          <Button variant="outline" size="sm" onClick={clear}>
            {t("squad.filters.clear")}
          </Button>
        )}
      </TableMessage>
    );
  } else {
    content = <SquadTable players={data} />;
  }

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <StatusControl value={filters.status} onChange={(status) => setFilters({ status })} />
        {hasPermission(permissionLevel, "EDIT_FULL") && (
          // A real link styled as a button: Base UI's Button would give the <a> role="button".
          <Link to={NEW_PLAYER_PATH} className={buttonVariants()}>
            <Plus aria-hidden="true" />
            {t("squad.addPlayer")}
          </Link>
        )}
      </div>
      <SquadFilterBar
        filters={filters}
        setFilters={setFilters}
        onClear={clear}
        clearCount={clearCount}
      />
      {/* Always in the DOM, so a screen reader announces the new count after a filter change. */}
      <p
        aria-live="polite"
        className="text-[0.8125rem] font-medium text-muted-foreground tabular-nums"
      >
        {data !== undefined && t("squad.count", { count: data.length })}
      </p>
      <div
        aria-busy={players.isPlaceholderData || undefined}
        className={cn(
          "overflow-hidden rounded-lg border border-border bg-card transition-opacity",
          players.isPlaceholderData && "opacity-60",
        )}
      >
        {content}
      </div>
    </div>
  );
}

function TableMessage({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-4 py-12 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
