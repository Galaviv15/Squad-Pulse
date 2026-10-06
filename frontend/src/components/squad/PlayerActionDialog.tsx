import { useQueryClient } from "@tanstack/react-query";
import { useRef, useState } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { FIELD_INVALID_CLASSES } from "@/components/form/fieldClasses";
import { FormField } from "@/components/form/FormField";
import { FormAlert } from "@/components/form/FormMessage";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ApiError } from "@/lib/api/errors";
import { PLAYER_ERROR_CODES } from "@/lib/squad/errorCodes";
import { jerseyNumberError, toNumber } from "@/lib/squad/form";
import { SQUAD_PATH } from "@/lib/squad/paths";
import {
  SQUAD_QUERY_KEY,
  useDeletePlayer,
  useReactivatePlayer,
  useReleasePlayer,
} from "@/lib/squad/players";
import type { Player } from "@/lib/squad/types";
import { cn } from "@/lib/utils";
import { ActionDialog } from "./ActionDialog";
import { actionErrorMessage, type FinalFocus } from "./playerDialogs";
import type { PlayerDialogKind } from "./playerActions";

/** Where a player dialog was opened: decides what "not found" offers. */
export type PlayerDialogPlace = "card" | "table";

/** The dialog a host has open: which one, for the player as it was when it opened. */
export interface PlayerDialogTarget {
  kind: PlayerDialogKind;
  player: Player;
}

interface PlayerActionDialogProps {
  target: PlayerDialogTarget;
  open: boolean;
  onClose(): void;
  onClosed?(): void;
  finalFocus?: FinalFocus;
  place: PlayerDialogPlace;
  /** After a delete (or a 404: already gone). The host leaves the card or shows the notice. */
  onDeleted(player: Player): Promise<void> | void;
}

/**
 * The release, re-activate or permanent-delete dialog for `target`. A host (the card, the squad
 * page) renders it outside any menu, controlled: a menu item or button only sets the target. The
 * request uses the version of `target.player`, the data the user confirmed from. Remount it (key)
 * for each new target, so no earlier error or field value carries over.
 */
export function PlayerActionDialog({ target, onDeleted, ...props }: PlayerActionDialogProps) {
  switch (target.kind) {
    case "release":
      return <ReleaseDialog player={target.player} {...props} />;
    case "reactivate":
      return <ReactivateDialog player={target.player} {...props} />;
    case "delete":
      return <DeleteDialog player={target.player} onDeleted={onDeleted} {...props} />;
  }
}

type DialogProps = Omit<PlayerActionDialogProps, "target" | "onDeleted"> & { player: Player };

function ReleaseDialog({ player, place, ...dialog }: DialogProps) {
  const { t } = useTranslation();
  const release = useReleasePlayer();

  return (
    <ActionDialog
      {...dialog}
      pending={release.isPending}
      title={t("squad.dialog.release.title", { name: player.fullName })}
      description={t("squad.dialog.release.text")}
      confirmLabel={t("squad.actions.release")}
      pendingLabel={t("squad.actions.releasing")}
      error={
        release.error && (
          <ActionError error={release.error} place={place} onClose={dialog.onClose} />
        )
      }
      onConfirm={async () => {
        try {
          await release.mutateAsync({ playerId: player.id, version: player.version });
          dialog.onClose();
        } catch {
          // Shown from release.error.
        }
      }}
    />
  );
}

function ReactivateDialog({ player, place, ...dialog }: DialogProps) {
  const { t } = useTranslation();
  const reactivate = useReactivatePlayer();
  const [jerseyNumber, setJerseyNumber] = useState(
    player.jerseyNumber === null ? "" : String(player.jerseyNumber),
  );
  const [fieldError, setFieldError] = useState<string | undefined>();
  const [submitted, setSubmitted] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  function showFieldError(key: string) {
    setFieldError(key);
    inputRef.current?.focus();
  }

  async function confirm() {
    setSubmitted(true);
    const invalid = jerseyNumberError(jerseyNumber);
    if (invalid !== undefined) {
      showFieldError(invalid);
      return;
    }
    reactivate.reset();
    try {
      await reactivate.mutateAsync({
        playerId: player.id,
        version: player.version,
        jerseyNumber: toNumber(jerseyNumber),
      });
      dialog.onClose();
    } catch (error) {
      if (!(error instanceof ApiError)) {
        return;
      }
      if (error.status === 409 && error.code === PLAYER_ERROR_CODES.JERSEY_NUMBER_TAKEN) {
        showFieldError("squad.form.errors.jerseyNumberTaken");
      } else if (error.status === 400 && error.fieldErrors.jerseyNumber !== undefined) {
        showFieldError("squad.form.errors.jerseyNumberRange");
      }
    }
  }

  // A field-level failure shows on the field only, not again as an alert.
  const fieldLevel =
    reactivate.error instanceof ApiError &&
    ((reactivate.error.status === 409 &&
      reactivate.error.code === PLAYER_ERROR_CODES.JERSEY_NUMBER_TAKEN) ||
      (reactivate.error.status === 400 && reactivate.error.fieldErrors.jerseyNumber !== undefined));

  return (
    <ActionDialog
      {...dialog}
      pending={reactivate.isPending}
      title={t("squad.dialog.reactivate.title", { name: player.fullName })}
      confirmLabel={t("squad.actions.reactivate")}
      pendingLabel={t("squad.actions.reactivating")}
      error={
        reactivate.error &&
        !fieldLevel && (
          <ActionError error={reactivate.error} place={place} onClose={dialog.onClose} />
        )
      }
      onConfirm={confirm}
    >
      <FormField
        id="reactivate-jersey-number"
        label={t("squad.fields.jerseyNumber")}
        hint={t("squad.dialog.reactivate.hint")}
        error={fieldError}
      >
        {(control) => (
          <Input
            {...control}
            ref={inputRef}
            inputMode="numeric"
            autoComplete="off"
            dir="ltr"
            className={cn("tabular-nums", FIELD_INVALID_CLASSES)}
            value={jerseyNumber}
            // Not disabled: a disabled field can't take focus back when the answer is about it.
            readOnly={reactivate.isPending}
            onChange={(event) => {
              setJerseyNumber(event.target.value);
              // Validated on submit, then live; a server error goes once the value changes.
              setFieldError(submitted ? jerseyNumberError(event.target.value) : undefined);
            }}
          />
        )}
      </FormField>
    </ActionDialog>
  );
}

function DeleteDialog({
  player,
  place,
  onDeleted,
  ...dialog
}: DialogProps & Pick<PlayerActionDialogProps, "onDeleted">) {
  const { t } = useTranslation();
  const remove = useDeletePlayer();

  return (
    <ActionDialog
      {...dialog}
      destructive
      pending={remove.isPending}
      title={t("squad.dialog.delete.title", { name: player.fullName })}
      description={t("squad.dialog.delete.text")}
      confirmLabel={t("squad.actions.delete")}
      pendingLabel={t("squad.actions.deleting")}
      error={
        remove.error && <ActionError error={remove.error} place={place} onClose={dialog.onClose} />
      }
      onConfirm={async () => {
        try {
          await remove.mutateAsync({ playerId: player.id });
        } catch {
          return; // Shown from remove.error.
        }
        await onDeleted(player);
      }}
    />
  );
}

/**
 * A player dialog's failure, as an alert inside it. 409s that mean the data shown is out of date
 * (stale version, already released / active, released) offer "טעינת הנתונים העדכניים": it closes
 * the dialog and refetches the squad queries (the write's onError has invalidated them already;
 * this makes sure the card or list shows the server's state). A 404 offers the squad: a link
 * from the card, the same refetch in the table (already on the squad; the row goes away).
 */
export function ActionError({
  error,
  place,
  onClose,
}: {
  error: Error;
  place: PlayerDialogPlace;
  onClose(): void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const { key, offer } = actionErrorMessage(error);

  function reload() {
    onClose();
    void queryClient.invalidateQueries({ queryKey: SQUAD_QUERY_KEY });
  }

  let action = null;
  if (offer === "reload" || (offer === "squad" && place === "table")) {
    action = (
      <Button type="button" variant="outline" size="sm" className="self-start" onClick={reload}>
        {t("squad.dialog.reload")}
      </Button>
    );
  } else if (offer === "squad") {
    action = (
      <Link to={SQUAD_PATH} className="self-start underline underline-offset-3">
        {t("squad.backToSquad")}
      </Link>
    );
  }
  return <FormAlert action={action}>{t(key)}</FormAlert>;
}
