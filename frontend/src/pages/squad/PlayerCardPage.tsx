import { useQueryClient } from "@tanstack/react-query";
import { ImageMinus, ImageUp, Pencil, User } from "lucide-react";
import { useRef, useState, type ChangeEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router";
import { FormAlert } from "@/components/form/FormMessage";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import { ActionDialog } from "@/components/squad/ActionDialog";
import { ActionError, PlayerActionDialog } from "@/components/squad/PlayerActionDialog";
import {
  MedicalStatusBadge,
  None,
  PositionChips,
  ReleasedBadge,
} from "@/components/squad/PlayerBadges";
import { playerActions, type PlayerDialogKind } from "@/components/squad/playerActions";
import { focusBack, useDialogHost } from "@/components/squad/playerDialogs";
import { Button, buttonVariants } from "@/components/ui/button";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { ageOn } from "@/lib/squad/age";
import { formatIsoDate } from "@/lib/squad/dates";
import { PREFERRED_FOOT_KEYS } from "@/lib/squad/labels";
import { PHOTO_ACCEPT, photoFileError, photoUploadErrorKey } from "@/lib/squad/photo";
import { editPlayerPath, playerPhotoPath } from "@/lib/squad/paths";
import {
  onPlayerDeleted,
  usePlayer,
  useRemovePlayerPhoto,
  useUploadPlayerPhoto,
} from "@/lib/squad/players";
import { deletedPlayerState } from "@/lib/squad/routeState";
import { squadReturnPath } from "@/lib/squad/squadReturnPath";
import type { Player } from "@/lib/squad/types";
import { cn } from "@/lib/utils";
import { BackToSquadLink, PlayerQueryState } from "./PlayerPageStates";

/**
 * /app/squad/:playerId: one player's card, released players included. The header (photo with its
 * controls, name as the <h2>, jersey number, chips and pills) with the action area at its end
 * (edit, release or re-activate, delete, back), then the details. A released player's content is
 * dimmed and has no edit link or photo controls; the action area isn't dimmed. The actions open
 * their dialogs here, outside the buttons. Its title ("כרטיס שחקן") comes from the route.
 */
export function PlayerCardPage() {
  const { playerId = "" } = useParams();
  const player = usePlayer(playerId);

  // A failed background refetch keeps the data it had: only a player with no data shows a state.
  if (player.data === undefined) {
    return <PlayerQueryState query={player} />;
  }
  return <PlayerCard player={player.data} />;
}

type CardDialog = { kind: PlayerDialogKind } | { kind: "removePhoto" };

function PlayerCard({ player }: { player: Player }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { permissionLevel } = useCurrentUser();
  const released = !player.active;
  const dim = released && "opacity-60";
  const canEditPhoto = player.active && hasPermission(permissionLevel, "EDIT_FULL");
  const dialogActions = playerActions(player, permissionLevel).filter(
    (action) => action.kind === "dialog",
  );

  // The dialog is opened with the player as shown then: its request sends that version.
  const { dialog, openDialog, close, closed } = useDialogHost<{
    which: CardDialog;
    player: Player;
  }>();

  const upload = useUploadPlayerPhoto();
  const removePhoto = useRemovePlayerPhoto();
  const fileInput = useRef<HTMLInputElement>(null);
  const uploadButton = useRef<HTMLButtonElement>(null);
  // Set by a successful removal: its button is about to go, so focus goes to the upload button.
  const photoRemoved = useRef(false);
  const [photoError, setPhotoError] = useState<string | null>(null);
  // Bumped after an upload: a replaced photo has the same path, so the image is fetched again.
  const [photoRevision, setPhotoRevision] = useState(0);
  const photoBusy = upload.isPending || removePhoto.isPending;

  async function uploadPhoto(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    // Cleared, so picking the same file again is a change too.
    event.target.value = "";
    if (file === undefined || photoBusy) {
      return;
    }
    const invalid = photoFileError(file);
    setPhotoError(invalid);
    if (invalid !== null) {
      return;
    }
    try {
      await upload.mutateAsync({ playerId: player.id, file });
      setPhotoRevision((revision) => revision + 1);
    } catch (error) {
      setPhotoError(photoUploadErrorKey(error));
    }
  }

  async function leaveDeleted(deleted: Player) {
    // Away from the card first: while it's mounted, dropping its query would refetch it (a 404).
    // To the squad as last seen in this tab; the squad page keeps its search when it clears the
    // notice from the state.
    await navigate(squadReturnPath(), {
      replace: true,
      state: deletedPlayerState(deleted.fullName),
    });
    onPlayerDeleted(queryClient, deleted.id);
  }

  return (
    <div className="flex flex-col gap-5">
      <section className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5 md:flex-row md:items-start md:justify-between md:p-6">
        <div className="flex flex-col gap-3">
          <div className={cn("flex items-center gap-4", dim)}>
            <div className="relative shrink-0">
              <ImageOrInitials
                path={player.hasPhoto ? playerPhotoPath(player.id) : null}
                refreshKey={photoRevision}
                name={player.fullName}
                fallbackIcon={User}
                className="size-24 rounded-full bg-secondary text-2xl font-semibold text-secondary-foreground"
                imageClassName="object-cover"
              />
              {photoBusy && (
                <span
                  role="status"
                  className="absolute inset-0 flex items-center justify-center rounded-full bg-foreground/60 text-xs font-medium text-background"
                >
                  {t(upload.isPending ? "squad.photo.uploading" : "squad.photo.removing")}
                </span>
              )}
            </div>
            <div className="flex min-w-0 flex-col gap-2">
              <div className="flex flex-wrap items-baseline gap-x-2.5 gap-y-1">
                <h2 className="text-xl font-bold break-words">{player.fullName}</h2>
                {player.jerseyNumber !== null && (
                  <span dir="ltr" className="text-xl font-bold text-muted-foreground tabular-nums">
                    #{player.jerseyNumber}
                  </span>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-1.5">
                <PositionChips
                  primary={player.primaryPosition}
                  secondary={player.secondaryPosition}
                />
                <MedicalStatusBadge status={player.medicalStatus} />
                {released && <ReleasedBadge />}
              </div>
            </div>
          </div>
          {canEditPhoto && (
            <div className="flex flex-wrap items-center gap-2">
              {/* Hidden: the button opens it, so it's reachable by keyboard through the button. */}
              <input
                ref={fileInput}
                type="file"
                accept={PHOTO_ACCEPT}
                className="hidden"
                tabIndex={-1}
                aria-hidden="true"
                onChange={(event) => void uploadPhoto(event)}
              />
              <Button
                ref={uploadButton}
                variant="outline"
                size="sm"
                disabled={photoBusy}
                onClick={() => fileInput.current?.click()}
              >
                <ImageUp aria-hidden="true" />
                {t(player.hasPhoto ? "squad.photo.replace" : "squad.photo.upload")}
              </Button>
              {player.hasPhoto && (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={photoBusy}
                  onClick={(event) => {
                    const opener = focusBack(event.currentTarget);
                    setPhotoError(null);
                    photoRemoved.current = false;
                    openDialog({ which: { kind: "removePhoto" }, player }, () =>
                      photoRemoved.current && uploadButton.current
                        ? uploadButton.current
                        : opener(),
                    );
                  }}
                >
                  <ImageMinus aria-hidden="true" />
                  {t("squad.photo.remove")}
                </Button>
              )}
            </div>
          )}
          {photoError !== null && <FormAlert>{t(photoError)}</FormAlert>}
        </div>
        {/* The action area: two columns on a phone, one row from md. */}
        <div className="grid grid-cols-2 gap-2 md:flex md:flex-wrap md:items-center md:justify-end">
          {player.active && hasPermission(permissionLevel, "EDIT_FULL") && (
            // A real link styled as a button: Base UI's Button would give the <a> role="button".
            <Link to={editPlayerPath(player.id)} className={buttonVariants({ variant: "outline" })}>
              <Pencil aria-hidden="true" />
              {t("squad.edit")}
            </Link>
          )}
          {dialogActions.map(({ labelKey, icon: Icon, dialog: kind, destructive }) => (
            <Button
              // Release and re-activate are one element whose label changes, so focus can return
              // to it after the dialog (see focusBack).
              key={kind === "delete" ? "delete" : "lifecycle"}
              variant={destructive ? "destructive" : "outline"}
              onClick={(event) =>
                openDialog({ which: { kind }, player }, focusBack(event.currentTarget))
              }
            >
              <Icon aria-hidden="true" />
              {t(labelKey)}
            </Button>
          ))}
          <BackToSquadLink size="default" />
        </div>
      </section>
      <section className={cn("rounded-lg border border-border bg-card p-5 md:p-6", dim)}>
        <dl className="grid grid-cols-1 gap-x-8 gap-y-4 md:grid-cols-2">
          <Detail label={t("squad.fields.dateOfBirth")}>
            <DateOfBirth value={player.dateOfBirth} />
          </Detail>
          <Detail label={t("squad.fields.heightCm")}>
            <NumberValue value={player.heightCm} />
          </Detail>
          <Detail label={t("squad.fields.weightKg")}>
            <NumberValue value={player.weightKg} />
          </Detail>
          <Detail label={t("squad.fields.preferredFoot")}>
            {player.preferredFoot === null ? (
              <None />
            ) : (
              t(PREFERRED_FOOT_KEYS[player.preferredFoot])
            )}
          </Detail>
          <Detail label={t("squad.fields.medicalStatus")}>
            <MedicalStatusBadge status={player.medicalStatus} />
          </Detail>
          <Detail label={t("squad.fields.primaryPosition")}>
            <PositionChips primary={player.primaryPosition} secondary={null} />
          </Detail>
          <Detail label={t("squad.fields.secondaryPosition")}>
            <PositionChips primary={null} secondary={player.secondaryPosition} />
          </Detail>
          <Detail label={t("squad.fields.jerseyNumber")}>
            <NumberValue value={player.jerseyNumber} />
          </Detail>
        </dl>
      </section>
      {dialog !== null &&
        (dialog.target.which.kind === "removePhoto" ? (
          <RemovePhotoDialog
            key={dialog.key}
            player={dialog.target.player}
            removePhoto={removePhoto}
            onRemoved={() => (photoRemoved.current = true)}
            open={dialog.open}
            onClose={close}
            onClosed={closed}
            finalFocus={dialog.finalFocus}
          />
        ) : (
          <PlayerActionDialog
            key={dialog.key}
            target={{ kind: dialog.target.which.kind, player: dialog.target.player }}
            place="card"
            open={dialog.open}
            onClose={close}
            onClosed={closed}
            finalFocus={dialog.finalFocus}
            onDeleted={leaveDeleted}
          />
        ))}
    </div>
  );
}

/**
 * "הסרת התמונה של <name>": the card's photo removal, confirmed. The mutation is the card's, so the
 * photo shows "מסיר…" while it runs too.
 */
function RemovePhotoDialog({
  player,
  removePhoto,
  onRemoved,
  open,
  onClose,
  onClosed,
  finalFocus,
}: {
  player: Player;
  removePhoto: ReturnType<typeof useRemovePlayerPhoto>;
  onRemoved(): void;
  open: boolean;
  onClose(): void;
  onClosed(): void;
  finalFocus: ReturnType<typeof focusBack>;
}) {
  const { t } = useTranslation();
  return (
    <ActionDialog
      open={open}
      onClose={onClose}
      onClosed={onClosed}
      finalFocus={finalFocus}
      destructive
      pending={removePhoto.isPending}
      title={t("squad.photo.removeTitle", { name: player.fullName })}
      description={t("squad.photo.removeText")}
      confirmLabel={t("squad.photo.removeConfirm")}
      pendingLabel={t("squad.photo.removing")}
      error={
        removePhoto.error && (
          <ActionError error={removePhoto.error} place="card" onClose={onClose} />
        )
      }
      onConfirm={async () => {
        removePhoto.reset();
        try {
          await removePhoto.mutateAsync({ playerId: player.id });
          onRemoved();
          onClose();
        } catch {
          // Shown from removePhoto.error.
        }
      }}
    />
  );
}

function Detail({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1">
      <dt className="text-[0.8125rem] font-medium text-muted-foreground">{label}</dt>
      <dd className="text-sm">{children}</dd>
    </div>
  );
}

function NumberValue({ value }: { value: number | null }) {
  return value === null ? (
    <None />
  ) : (
    <span dir="ltr" className="tabular-nums">
      {value}
    </span>
  );
}

/** dd.MM.yyyy from the date's parts, with the age today beside it: "20.05.1998 (גיל 28)". */
function DateOfBirth({ value }: { value: string | null }) {
  const { t } = useTranslation();
  const formatted = formatIsoDate(value);
  if (formatted === null) {
    return <None />;
  }
  const age = ageOn(value, new Date());
  return (
    <span className="tabular-nums">
      <span dir="ltr">{formatted}</span>
      {age !== null && <> {t("squad.player.age", { age })}</>}
    </span>
  );
}
