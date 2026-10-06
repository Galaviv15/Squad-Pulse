import { useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate } from "react-router";
import { FormNotice } from "@/components/form/FormMessage";
import { PlayerActionDialog, type PlayerDialogTarget } from "@/components/squad/PlayerActionDialog";
import { focusBack, useDialogHost } from "@/components/squad/playerDialogs";
import { SquadFilterBar } from "@/components/squad/SquadFilterBar";
import { SquadTable } from "@/components/squad/SquadTable";
import { StatusControl } from "@/components/squad/StatusControl";
import { Button, buttonVariants } from "@/components/ui/button";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { hasFilterBarFilters } from "@/lib/squad/filters";
import { EMPTY_SQUAD_KEYS } from "@/lib/squad/labels";
import { NEW_PLAYER_PATH } from "@/lib/squad/paths";
import { onPlayerDeleted, useSquadPlayers } from "@/lib/squad/players";
import { deletedPlayerNameFromState } from "@/lib/squad/routeState";
import { useSquadFilters } from "@/lib/squad/useSquadFilters";
import { cn } from "@/lib/utils";

/**
 * /app/squad: the squad table. The status control and the "add player" link (EDIT_FULL and up),
 * the filter bar, the count, and the table. The filters live in the URL (useSquadFilters); the
 * list comes from useSquadPlayers, in the server's order. Its title comes from the route.
 *
 * The rows' in-place actions (release, re-activate, delete) open their dialog here, outside the
 * table. "<name> נמחק לצמיתות." shows above the table after a delete, from a row or (carried in
 * router state) from the card.
 */
export function SquadPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { permissionLevel } = useCurrentUser();
  // Before useSquadFilters: if both rewrite the URL on mount, the filters' normalization (which
  // drops the state too) is the last word.
  const [deletedName, setDeletedName] = useDeletedPlayerNotice();
  const { filters, setFilters, clearFilters } = useSquadFilters();
  const { dialog, openDialog, close, closed } = useDialogHost<PlayerDialogTarget>();
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
    content = (
      <SquadTable
        players={data}
        onDialog={(kind, player, trigger) => openDialog({ kind, player }, focusBack(trigger))}
      />
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {deletedName !== null && (
        <FormNotice icon="success">{t("squad.deletedNotice", { name: deletedName })}</FormNotice>
      )}
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
      {/* Always in the DOM, so a screen reader announces the new count after a filter change.
          Empty on an empty list, whose message says so already. */}
      <p
        aria-live="polite"
        className="text-[0.8125rem] font-medium text-muted-foreground tabular-nums"
      >
        {data !== undefined && data.length > 0 && t("squad.count", { count: data.length })}
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
      {dialog !== null && (
        <PlayerActionDialog
          key={dialog.key}
          target={dialog.target}
          place="table"
          open={dialog.open}
          onClose={close}
          onClosed={closed}
          finalFocus={dialog.finalFocus}
          onDeleted={(player) => {
            onPlayerDeleted(queryClient, player.id);
            setDeletedName(player.fullName);
            close();
          }}
        />
      )}
    </div>
  );
}

/**
 * The deleted-player notice: from the card's router state on arrival, or set after a delete from
 * a row. The state is read once, then removed from the history entry (a replace to the same URL
 * without state), so neither a reload nor coming Back to this entry shows it again.
 */
function useDeletedPlayerNotice() {
  const location = useLocation();
  const navigate = useNavigate();
  const fromState = deletedPlayerNameFromState(location.state);
  const notice = useState(fromState);

  const { pathname, search, hash } = location;
  useEffect(() => {
    if (fromState !== null) {
      void navigate({ pathname, search, hash }, { replace: true, state: null });
    }
  }, [fromState, pathname, search, hash, navigate]);

  return notice;
}

function TableMessage({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 px-4 py-12 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}
