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
              dataUpdatedAt={stats.dataUpdatedAt}
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

/**
 * A row whose focus is settled once the stats refetched after a write (or a return from the
 * create-player form) have rendered: the rows that followed and preceded it in the list as it was
 * then, in the server's order, captured before the change (the refetched list may no longer have
 * the row).
 */
interface FocusCapture {
  rowId: string;
  /** The ids after the row, in the server's order. */
  next: string[];
  /** The ids before it, nearest first. */
  previous: string[];
  /** The stats' dataUpdatedAt when captured: only a later answer settles it. */
  since: number;
  /** The write succeeded (or the page arrived back): settle on the next answer. */
  armed: boolean;
}

function StatsContent({
  rows,
  dataUpdatedAt,
  canEdit,
  arrival,
  onArrivalHandled,
}: {
  rows: PlayerStatsRow[];
  /** When the stats shown were fetched: a refetch that lands changes it, even with equal data. */
  dataUpdatedAt: number;
  canEdit: boolean;
  arrival: Arrival;
  onArrivalHandled(): void;
}) {
  const { t } = useTranslation();
  const { filter, setFilter } = useStatsFilter();
  const picker = useDialogHost<PlayerStatsRow>();
  const [notice, setNotice] = useState<{ text: string; key: number } | null>(null);
  const rowHeaders = useRef(new Map<string, HTMLElement>());
  const filterControl = useRef<HTMLDivElement>(null);
  const pickerLinked = useRef(false);

  /*
   * After a successful link write the control the user acted on goes away once the list is
   * refetched ("אישור" and "אחר…" on a link, the "⋯" menu's trigger on an unlink, the picker's
   * opener), so focus goes to the row's header cell (tabIndex -1), which stays — at once (focusRow).
   * Under "ממתינים לקישור" the row itself goes too once the refetched list shows it linked, which
   * is the main flow (a new club links or creates ~33 players in a row), so focus is settled again
   * once that answer has rendered (settleFocus): the row's header if it's still shown; else the next
   * row after it, in the server's order as captured before the change, that's still shown — the
   * next player waiting for a link —; else the nearest one before it; else, with the filtered table now empty, the filter's selected option. Never
   * stealing focus: only if it's still in that row, or lost (null / body, as when the row's header
   * was removed under it, or after the navigation back from the create-player form).
   */
  const rowHeader = useCallback((rowId: string) => {
    const header = rowHeaders.current.get(rowId);
    return header?.isConnected ? header : undefined;
  }, []);
  const focusIsFree = (header: HTMLElement | undefined) => {
    const active = document.activeElement;
    const inRow = header?.closest("tr")?.contains(active) ?? false;
    return inRow || active === null || active === document.body;
  };
  const focusRow = (rowId: string) => {
    const header = rowHeader(rowId);
    if (header !== undefined && focusIsFree(header)) {
      header.focus();
    }
  };
  /** Where focus goes for `capture` now: the row, a neighbour, or the filter's selected option. */
  const focusTarget = (capture: FocusCapture): HTMLElement | undefined => {
    const firstShown = (ids: string[]) => ids.map(rowHeader).find((header) => header !== undefined);
    return (
      rowHeader(capture.rowId) ??
      firstShown(capture.next) ??
      firstShown(capture.previous) ??
      filterControl.current?.querySelector<HTMLElement>('[role="radio"][aria-checked="true"]') ??
      undefined
    );
  };

  const shown = canEdit ? filterStatsRows(rows, filter) : rows;

  // The one pending capture (a newer write replaces it). latestUpdate mirrors dataUpdatedAt for
  // the callbacks, which run outside render.
  const pendingFocus = useRef<FocusCapture | null>(null);
  const latestUpdate = useRef(dataUpdatedAt);
  // The neighbours come from the whole list in the server's order, not only the rows shown: the
  // row may already be hidden (back from the create-player form, the save's refetch has usually
  // landed while the form was still open). Rows not shown have no header, so they're skipped.
  const captureFocus = (rowId: string): FocusCapture => {
    const ids = rows.map((row) => row.id);
    const index = ids.indexOf(rowId);
    const capture: FocusCapture = {
      rowId,
      next: index < 0 ? [] : ids.slice(index + 1),
      previous: index < 0 ? [] : ids.slice(0, index).reverse(),
      since: dataUpdatedAt,
      armed: false,
    };
    pendingFocus.current = capture;
    return capture;
  };
  /** Settles the armed capture once an answer newer than it has rendered; once. */
  const settleFocus = () => {
    const capture = pendingFocus.current;
    if (capture === null || !capture.armed || latestUpdate.current <= capture.since) {
      return;
    }
    pendingFocus.current = null;
    if (focusIsFree(rowHeader(capture.rowId))) {
      focusTarget(capture)?.focus();
    }
  };
  const settleFocusRef = useRef(settleFocus);
  useEffect(() => {
    settleFocusRef.current = settleFocus;
  });
  const armFocus = (rowId: string) => {
    const capture = pendingFocus.current;
    if (capture?.rowId === rowId) {
      capture.armed = true;
      settleFocus();
    }
  };
  // Runs after the refetched list has rendered (the headers' refs are set by then).
  useEffect(() => {
    latestUpdate.current = dataUpdatedAt;
    settleFocusRef.current();
  }, [dataUpdatedAt]);

  // Back from the create-player form: the row's neighbours from the list as it is now, focus on the
  // row (or already its neighbour) at once, settled again after the next answer;
  // after a save, the notice goes in a tick after mounting, into the live region that's already
  // there, so it's announced. Handling it clears `arrival` (so it runs once); the timer is cleared
  // only on unmount, not by that re-run.
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(noticeTimer.current), []);
  const handleArrival = useRef<(arrived: NonNullable<Arrival>) => void>(() => {});
  useEffect(() => {
    handleArrival.current = (arrived) => {
      const capture = captureFocus(arrived.rowId);
      capture.armed = true;
      focusTarget(capture)?.focus();
    };
  });
  useEffect(() => {
    if (arrival === null) {
      return;
    }
    onArrivalHandled();
    handleArrival.current(arrival);
    const name = arrival.createdPlayerName;
    if (name !== null) {
      noticeTimer.current = setTimeout(() =>
        setNotice((previous) => ({
          text: t("stats.createPlayer.created", { name }),
          key: (previous?.key ?? 0) + 1,
        })),
      );
    }
  }, [arrival, onArrivalHandled, t]);

  // The write's callback is created once; it reaches the current focus functions through a ref.
  const focusRowRef = useRef((rowId: string) => {
    void rowId;
  });
  useEffect(() => {
    focusRowRef.current = (rowId) => {
      focusRow(rowId);
      armFocus(rowId);
    };
  });

  const writes = useRowWrites(
    useCallback(
      (row: PlayerStatsRow, kind: "linked" | "unlinked") => {
        if (kind === "unlinked") {
          setNotice((previous) => ({ text: t("stats.unlinked"), key: (previous?.key ?? 0) + 1 }));
        }
        focusRowRef.current(row.id);
      },
      [t],
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

  return (
    <div className="flex flex-col gap-4">
      {canEdit && (
        <div ref={filterControl}>
          <StatsFilterControl rows={rows} value={filter} onChange={setFilter} />
        </div>
      )}
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
            captureFocus(row.id);
            writes.confirmSuggestion(row);
          }}
          onPick={(row, opener) => {
            setNotice(null);
            writes.clearError(row.id);
            pickerLinked.current = false;
            const capture = captureFocus(row.id);
            picker.openDialog(row, () => {
              // Linked: the opener is about to be replaced, so focus the row instead (or, if the
              // refetch already hid it, its neighbour).
              const target = pickerLinked.current ? focusTarget(capture) : undefined;
              return target ?? focusBack(opener)();
            });
          }}
          onUnlink={(row) => {
            setNotice(null);
            captureFocus(row.id);
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
            armFocus(picker.dialog?.target.id ?? "");
          }}
        />
      )}
    </div>
  );
}
