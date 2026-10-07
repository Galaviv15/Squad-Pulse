import { useQueryClient } from "@tanstack/react-query";
import { Plus } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate } from "react-router";
import { FormNotice } from "@/components/form/FormMessage";
import { PlayerActionDialog, type PlayerDialogTarget } from "@/components/squad/PlayerActionDialog";
import type { PlayerDialogKind } from "@/components/squad/playerActions";
import { focusBack, useDialogHost } from "@/components/squad/playerDialogs";
import { SquadCards } from "@/components/squad/SquadCards";
import { SquadFilterBar } from "@/components/squad/SquadFilterBar";
import { SquadTable } from "@/components/squad/SquadTable";
import { StatusControl } from "@/components/squad/StatusControl";
import { ViewToggle } from "@/components/squad/ViewToggle";
import { Button, buttonVariants } from "@/components/ui/button";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { hasFilterBarFilters } from "@/lib/squad/filters";
import { EMPTY_SQUAD_KEYS } from "@/lib/squad/labels";
import { NEW_PLAYER_PATH } from "@/lib/squad/paths";
import { onPlayerDeleted, useSquadPlayers } from "@/lib/squad/players";
import { deletedPlayerNameFromState } from "@/lib/squad/routeState";
import type { Player } from "@/lib/squad/types";
import { useSquadPageQuery } from "@/lib/squad/useSquadPageQuery";
import { cn } from "@/lib/utils";

/**
 * /app/squad: the squad. The status control, the view toggle and the "add player" link (EDIT_FULL
 * and up), the filter bar, the count, and the list as a table or as cards. The filters and the
 * view live in the URL (useSquadPageQuery); the list comes from useSquadPlayers, in the server's
 * order, the same for both views (switching views doesn't refetch it). Its title comes from the
 * route.
 *
 * The rows' and cards' in-place actions (release, re-activate, delete) open their dialog here,
 * outside the list. "<name> נמחק לצמיתות." shows above the toolbar after a delete, from a row, a
 * card or (carried in router state) the player card.
 */
export function SquadPage() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { permissionLevel } = useCurrentUser();
  // Before useSquadPageQuery: if both rewrite the URL on mount, the page query's normalization
  // (which drops the state too) is the last word.
  const [deletedName, setDeletedName] = useDeletedPlayerNotice();
  const { filters, setFilters, clearFilters, view, setView } = useSquadPageQuery();
  const { dialog, openDialog, close, closed } = useDialogHost<PlayerDialogTarget>();
  const players = useSquadPlayers(filters);
  const [clearCount, setClearCount] = useState(0);

  function clear() {
    clearFilters();
    setClearCount((count) => count + 1);
  }

  const { data } = players;
  const busy = players.isPlaceholderData;
  const onDialog = (kind: PlayerDialogKind, player: Player, trigger: HTMLElement | null) =>
    openDialog({ kind, player }, focusBack(trigger));
  let content: ReactNode;
  if (data === undefined) {
    content =
      players.isError && !players.isFetching ? (
        <ListMessage busy={busy}>
          <p>{t("squad.loadError")}</p>
          <Button variant="outline" size="sm" onClick={() => void players.refetch()}>
            {t("squad.retry")}
          </Button>
        </ListMessage>
      ) : (
        <ListMessage busy={busy}>
          <p role="status">{t("squad.loading")}</p>
        </ListMessage>
      );
  } else if (data.length === 0) {
    const filtered = hasFilterBarFilters(filters);
    content = (
      <ListMessage busy={busy}>
        <p>{t(filtered ? "squad.empty.filtered" : EMPTY_SQUAD_KEYS[filters.status])}</p>
        {filtered && (
          <Button variant="outline" size="sm" onClick={clear}>
            {t("squad.filters.clear")}
          </Button>
        )}
      </ListMessage>
    );
  } else if (view === "cards") {
    // No frame: the cards are cards themselves.
    content = <SquadCards players={data} busy={busy} onDialog={onDialog} />;
  } else {
    content = (
      <ListFrame busy={busy}>
        <SquadTable players={data} onDialog={onDialog} />
      </ListFrame>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {/* The deleted-player notice's live region, always there so the notice is announced when
          its text goes in (empty, it cancels the column's gap). */}
      <div role="status" className="empty:-mb-4">
        {deletedName !== null && (
          <FormNotice icon="success" live={false}>
            {t("squad.deletedNotice", { name: deletedName })}
          </FormNotice>
        )}
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <StatusControl value={filters.status} onChange={(status) => setFilters({ status })} />
        <div className="flex flex-wrap items-center gap-3">
          <ViewToggle value={view} onChange={setView} />
          {hasPermission(permissionLevel, "EDIT_FULL") && (
            // A real link styled as a button: Base UI's Button would give the <a> role="button".
            <Link to={NEW_PLAYER_PATH} className={buttonVariants()}>
              <Plus aria-hidden="true" />
              {t("squad.addPlayer")}
            </Link>
          )}
        </div>
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
      {content}
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
 * without state), so neither a reload nor coming Back to this entry shows it again. Arriving with
 * it, the text goes in a tick after the page mounted, so it lands in a live region that's already
 * there and is announced.
 */
function useDeletedPlayerNotice() {
  const location = useLocation();
  const navigate = useNavigate();
  const fromState = deletedPlayerNameFromState(location.state);
  const [arrivedWith] = useState(fromState);
  const notice = useState<string | null>(null);
  const setNotice = notice[1];

  const { pathname, search, hash } = location;
  useEffect(() => {
    if (fromState !== null) {
      void navigate({ pathname, search, hash }, { replace: true, state: null });
    }
  }, [fromState, pathname, search, hash, navigate]);

  useEffect(() => {
    if (arrivedWith === null) {
      return;
    }
    const timer = setTimeout(() => setNotice(arrivedWith));
    return () => clearTimeout(timer);
  }, [arrivedWith, setNotice]);

  return notice;
}

/**
 * The table's frame, and the messages' (loading, error, empty) in both views: a bordered card,
 * dimmed and aria-busy while a new filter's list loads (the previous one still shown). The cards
 * view has none; SquadCards marks its grid busy the same way.
 */
function ListFrame({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <div
      aria-busy={busy || undefined}
      className={cn(
        "overflow-hidden rounded-lg border border-border bg-card transition-opacity",
        busy && "opacity-60",
      )}
    >
      {children}
    </div>
  );
}

function ListMessage({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <ListFrame busy={busy}>
      <div className="flex flex-col items-center justify-center gap-3 px-4 py-12 text-center text-sm text-muted-foreground">
        {children}
      </div>
    </ListFrame>
  );
}
