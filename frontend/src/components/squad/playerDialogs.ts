import { useCallback, useState } from "react";
import { ApiError } from "@/lib/api/errors";
import { PLAYER_ERROR_CODES } from "@/lib/squad/errorCodes";

/** Where focus goes when a dialog closes, as Base UI's Popup `finalFocus` takes it. */
export type FinalFocus = () => HTMLElement | true;

/**
 * Focus back to `opener` (the button or menu trigger the dialog was opened from) if it's still in
 * the page when the dialog closes, else Base UI's default. Base UI resolves this as the dialog
 * closes, before the write's new data has rendered (TanStack Query notifies on a later tick): an
 * opener that's about to be replaced must be one element whose label changes (the card's
 * release / re-activate button), or the host must pick the target itself (photo removal).
 */
export function focusBack(opener: HTMLElement | null): FinalFocus {
  return () => (opener?.isConnected ? opener : true);
}

interface DialogHostState<T> {
  target: T;
  /** A new one per opening: the dialog is remounted, with no earlier error or input. */
  key: number;
  open: boolean;
  finalFocus: FinalFocus;
}

/**
 * A host's one dialog, controlled: `openDialog` from a button or a menu item, `close` when the
 * dialog asks, `closed` once its exit animation is done (the target is kept until then, so the
 * closing dialog keeps its content).
 */
export function useDialogHost<T>() {
  const [dialog, setDialog] = useState<DialogHostState<T> | null>(null);
  const openDialog = useCallback(
    (target: T, finalFocus: FinalFocus) =>
      setDialog((previous) => ({ target, key: (previous?.key ?? 0) + 1, open: true, finalFocus })),
    [],
  );
  const close = useCallback(
    () => setDialog((previous) => previous && { ...previous, open: false }),
    [],
  );
  const closed = useCallback(
    () => setDialog((previous) => (previous !== null && !previous.open ? null : previous)),
    [],
  );
  return { dialog, openDialog, close, closed };
}

/** What a failed player action says (an i18n key) and what it offers with it. */
export interface ActionErrorMessage {
  key: string;
  /** "reload": close and refetch; "squad": go to the squad; null: nothing (retry is the button). */
  offer: "reload" | "squad" | null;
}

/**
 * A player dialog's failure (release, re-activate, delete, photo removal): 403 no permission; 404
 * not found; the 409s that mean the data shown is out of date (stale version, already released or
 * active, released) with a reload; anything else (another 409, 5xx, no response) the generic
 * failure. Field-level failures (a taken jersey number) are the dialog's own.
 */
export function actionErrorMessage(error: Error): ActionErrorMessage {
  if (error instanceof ApiError) {
    if (error.status === 403) {
      return { key: "squad.noPermission", offer: null };
    }
    if (error.status === 404) {
      return { key: "squad.player.notFound", offer: "squad" };
    }
    if (error.status === 409) {
      switch (error.code) {
        case PLAYER_ERROR_CODES.STALE_VERSION:
          return { key: "squad.dialog.stale", offer: "reload" };
        case PLAYER_ERROR_CODES.PLAYER_ALREADY_RELEASED:
          return { key: "squad.dialog.alreadyReleased", offer: "reload" };
        case PLAYER_ERROR_CODES.PLAYER_ALREADY_ACTIVE:
          return { key: "squad.dialog.alreadyActive", offer: "reload" };
        case PLAYER_ERROR_CODES.PLAYER_RELEASED:
          return { key: "squad.form.released", offer: "reload" };
      }
    }
  }
  return { key: "squad.dialog.failed", offer: null };
}
