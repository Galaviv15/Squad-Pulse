import { useCallback, useEffect, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router";
import { FormAlert } from "@/components/form/FormMessage";
import { LastUpdated, QueryContent } from "@/components/matches/MatchDataStates";
import { RefreshControl } from "@/components/matches/RefreshControl";
import { focusBack, useDialogHost } from "@/components/squad/playerDialogs";
import { LinkPickerDialog } from "@/components/stats/LinkPickerDialog";
import { StatsFilterControl } from "@/components/stats/StatsFilterControl";
import { StatsTable } from "@/components/stats/StatsTable";
import { useRowWrites } from "@/components/stats/useRowWrites";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { usePlayerStats, type PlayerStats, type PlayerStatsRow } from "@/lib/matches/playerStats";
import { filterStatsRows, useStatsFilter } from "@/lib/matches/statsFilter";
import { statsReturnFromState } from "@/lib/matches/statsReturn";

/**
 * /app/stats (KAN-74): the club's scraped player season statistics, all competitions together, and
 * their links to the club's own players. Everyone (VIEW_ONLY up) sees the table and the linked
 * names; EDIT_FULL and up also get the refresh control, the filter and counts, the suggestions and
 * the link controls. Below EDIT_FULL none of those is rendered, so neither GET /matches/refresh nor
 * GET /squad/players (the picker's) is ever requested.
 */
export function StatsPage() {
  const { t } = useTranslation();
  const { permissionLevel } = useCurrentUser();
  const canEdit = hasPermission(permissionLevel, "EDIT_FULL");
  const stats = usePlayerStats();
  const [arrival, clearArrival] = useArrival();

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="flex flex-col gap-1">
          <p className="text-sm text-muted-foreground">{t("stats.description")}</p>
          <LastUpdated at={stats.data?.lastUpdatedAt ?? null} />
        </div>
        {canEdit && <RefreshControl />}
      </div>
      <QueryContent query={stats} errorMessage={t("stats.loadError")}>
        {(data: PlayerStats) =>
          data.state === "OK" ? (
            <StatsContent
              rows={data.players}
              canEdit={canEdit}
              arrival={arrival}
              onArrivalHandled={clearArrival}
            />
          ) : (
            <p className="text-sm text-muted-foreground">
              {t(data.state === "NOT_MAPPED" ? "matches.notMapped" : "stats.noData")}
            </p>
          )
        }
      </QueryContent>
    </div>
  );
}

type Arrival = ReturnType<typeof statsReturnFromState>;

/**
 * Coming back from the create-player form (KAN-77): the row to focus and, after a save, the
 * created player's name to announce. Read from history state once, then removed from the entry (a
 * replace to the same URL without state), so neither a reload nor Back repeats it; handled once,
 * by StatsContent, then forgotten.
 */
function useArrival() {
  const location = useLocation();
  const navigate = useNavigate();
  const fromState = statsReturnFromState(location.state);
  const [arrival, setArrival] = useState<Arrival>(fromState);

  const { pathname, search, hash } = location;
  const hasState = fromState !== null;
  useEffect(() => {
    if (hasState) {
      void navigate({ pathname, search, hash }, { replace: true, state: null });
    }
  }, [hasState, pathname, search, hash, navigate]);

  return [arrival, useCallback(() => setArrival(null), [])] as const;
}

function StatsContent({
  rows,
  canEdit,
  arrival,
  onArrivalHandled,
}: {
  rows: PlayerStatsRow[];
  canEdit: boolean;
  arrival: Arrival;
  onArrivalHandled(): void;
}) {
  const { t } = useTranslation();
  const { filter, setFilter } = useStatsFilter();
  const picker = useDialogHost<PlayerStatsRow>();
  const [notice, setNotice] = useState<{ text: string; key: number } | null>(null);
  const rowHeaders = useRef(new Map<string, HTMLElement>());
  const pickerLinked = useRef(false);

  /*
   * After a successful link write the control the user acted on goes away once the list is
   * refetched ("אישור" and "אחר…" on a link, the "⋯" menu's trigger on an unlink, the picker's
   * opener), so focus goes to the row's header cell (tabIndex -1), which stays. The one mechanism
   * for all three: the row writes call focusRow; the picker's finalFocus returns rowHeader.
   */
  const rowHeader = useCallback((rowId: string) => {
    const header = rowHeaders.current.get(rowId);
    return header?.isConnected ? header : undefined;
  }, []);
  const focusRow = useCallback(
    (rowId: string) => {
      const header = rowHeader(rowId);
      const active = document.activeElement;
      const inRow = header?.closest("tr")?.contains(active) ?? false;
      // Only if focus was in that row or lost: never steal it from where the user has moved on.
      if (header !== undefined && (inRow || active === null || active === document.body)) {
        header.focus();
      }
    },
    [rowHeader],
  );

  // Back from the create-player form: focus the row (it can be filtered out once the refetched
  // list shows it linked); after a save, the notice goes in a tick after mounting, into the live
  // region that's already there, so it's announced. Handling it clears `arrival` (so it runs once);
  // the timer is cleared only on unmount, not by that re-run.
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(noticeTimer.current), []);
  useEffect(() => {
    if (arrival === null) {
      return;
    }
    onArrivalHandled();
    rowHeader(arrival.rowId)?.focus();
    const name = arrival.createdPlayerName;
    if (name !== null) {
      noticeTimer.current = setTimeout(() =>
        setNotice((previous) => ({
          text: t("stats.createPlayer.created", { name }),
          key: (previous?.key ?? 0) + 1,
        })),
      );
    }
  }, [arrival, onArrivalHandled, rowHeader, t]);

  const writes = useRowWrites(
    useCallback(
      (row: PlayerStatsRow, kind: "linked" | "unlinked") => {
        if (kind === "unlinked") {
          setNotice((previous) => ({ text: t("stats.unlinked"), key: (previous?.key ?? 0) + 1 }));
        }
        focusRow(row.id);
      },
      [focusRow, t],
    ),
  );

  // A failure on a row that's gone (a refresh replaced the snapshot) shows above the table.
  const ids = new Set(rows.map((row) => row.id));
  const orphanErrors = [
    ...new Set(
      Object.entries(writes.errors)
        .filter(([id, key]) => !ids.has(id) && key !== undefined)
        .map(([, key]) => key as string),
    ),
  ];

  const shown = canEdit ? filterStatsRows(rows, filter) : rows;

  return (
    <div className="flex flex-col gap-4">
      {canEdit && <StatsFilterControl rows={rows} value={filter} onChange={setFilter} />}
      {/* The notices' live region (unlinked; created and linked, KAN-77), always there so the text
          is announced as it goes in. */}
      <div role="status" className="empty:-mb-4">
        {notice !== null && (
          <span key={notice.key} className="text-[0.8125rem] font-medium text-success">
            {notice.text}
          </span>
        )}
      </div>
      {orphanErrors.map((key) => (
        <FormAlert key={key}>{t(key)}</FormAlert>
      ))}
      {canEdit && filter === "unlinked" && shown.length === 0 ? (
        <p className="text-sm text-muted-foreground">{t("stats.filter.allLinked")}</p>
      ) : (
        <StatsTable
          rows={shown}
          canEdit={canEdit}
          pending={writes.pending}
          errors={writes.errors}
          rowHeaderRef={(rowId, element) => {
            if (element === null) {
              rowHeaders.current.delete(rowId);
            } else {
              rowHeaders.current.set(rowId, element);
            }
          }}
          onConfirmSuggestion={(row) => {
            setNotice(null);
            writes.confirmSuggestion(row);
          }}
          onPick={(row, opener) => {
            setNotice(null);
            writes.clearError(row.id);
            pickerLinked.current = false;
            picker.openDialog(row, () => {
              // Linked: the opener is about to be replaced, so focus the row instead.
              const header = pickerLinked.current ? rowHeader(row.id) : undefined;
              return header ?? focusBack(opener)();
            });
          }}
          onUnlink={(row) => {
            setNotice(null);
            writes.unlinkRow(row);
          }}
        />
      )}
      {picker.dialog !== null && (
        <LinkPickerDialog
          key={picker.dialog.key}
          row={picker.dialog.target}
          rows={rows}
          open={picker.dialog.open}
          onClose={picker.close}
          onClosed={picker.closed}
          finalFocus={picker.dialog.finalFocus}
          onLinked={() => {
            pickerLinked.current = true;
          }}
        />
      )}
    </div>
  );
}
