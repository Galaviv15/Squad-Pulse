import { useCallback, useRef, useState } from "react";
import { useTranslation } from "react-i18next";
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
            <StatsContent rows={data.players} canEdit={canEdit} />
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

function StatsContent({ rows, canEdit }: { rows: PlayerStatsRow[]; canEdit: boolean }) {
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
      {/* The unlink notice's live region, always there so the text is announced as it goes in. */}
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
