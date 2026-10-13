import { useCallback, useRef, useState } from "react";
import {
  linkErrorKey,
  useLinkStatsPlayer,
  useUnlinkStatsRow,
  type PlayerStatsRow,
} from "@/lib/matches/playerStats";

/**
 * The stats table's in-place link writes ("אישור" on a suggestion, "ביטול קישור"), per row: one
 * write at a time per row (a ref drops a second click before the disabled state renders), the
 * rows with one in flight, and each row's last failure (an i18n key, cleared by its next write).
 * The picker's writes are its own. `onDone(row, kind)` runs after a successful write.
 */
export function useRowWrites(onDone: (row: PlayerStatsRow, kind: "linked" | "unlinked") => void) {
  const link = useLinkStatsPlayer();
  const unlink = useUnlinkStatsRow();
  const running = useRef(new Set<string>());
  const [pending, setPending] = useState<ReadonlySet<string>>(new Set());
  const [errors, setErrors] = useState<Readonly<Partial<Record<string, string>>>>({});

  const run = useCallback(
    (row: PlayerStatsRow, write: () => Promise<unknown>, kind: "linked" | "unlinked") => {
      if (running.current.has(row.id)) {
        return;
      }
      running.current.add(row.id);
      setPending(new Set(running.current));
      setErrors((previous) => without(previous, row.id));
      write()
        .then(
          () => onDone(row, kind),
          (error: Error) =>
            setErrors((previous) => ({ ...previous, [row.id]: linkErrorKey(error) })),
        )
        .finally(() => {
          running.current.delete(row.id);
          setPending(new Set(running.current));
        });
    },
    [onDone],
  );

  const confirmSuggestion = (row: PlayerStatsRow) => {
    const suggested = row.suggestedPlayer;
    if (suggested !== null) {
      run(row, () => link.mutateAsync({ rowId: row.id, playerId: suggested.id }), "linked");
    }
  };

  const unlinkRow = (row: PlayerStatsRow) =>
    run(row, () => unlink.mutateAsync({ rowId: row.id }), "unlinked");

  /** Forgets a row's failure (e.g. when the picker opens, which shows its own). */
  const clearError = (rowId: string) => setErrors((previous) => without(previous, rowId));

  return { pending, errors, confirmSuggestion, unlinkRow, clearError };
}

function without<T>(record: Readonly<Partial<Record<string, T>>>, key: string) {
  const copy = { ...record };
  delete copy[key];
  return copy;
}
