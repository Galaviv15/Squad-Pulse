import { Pencil, User } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useParams } from "react-router";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import {
  MedicalStatusBadge,
  None,
  PositionChips,
  ReleasedBadge,
} from "@/components/squad/PlayerBadges";
import { buttonVariants } from "@/components/ui/button";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { ageOn } from "@/lib/squad/age";
import { formatIsoDate } from "@/lib/squad/dates";
import { PREFERRED_FOOT_KEYS } from "@/lib/squad/labels";
import { editPlayerPath, playerPhotoPath } from "@/lib/squad/paths";
import { usePlayer } from "@/lib/squad/players";
import type { Player } from "@/lib/squad/types";
import { cn } from "@/lib/utils";
import { BackToSquadLink, PlayerQueryState } from "./PlayerPageStates";

/**
 * /app/squad/:playerId: one player's card, released players included. The header (photo, name as
 * the <h2>, jersey number, chips and pills) with the action area at its end, then the details.
 * A released player's content is dimmed and has no edit link; the action area isn't dimmed.
 * Its title ("כרטיס שחקן") comes from the route.
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

function PlayerCard({ player }: { player: Player }) {
  const { t } = useTranslation();
  const { permissionLevel } = useCurrentUser();
  const released = !player.active;
  const dim = released && "opacity-60";

  return (
    <div className="flex flex-col gap-5">
      <section className="flex flex-col gap-4 rounded-lg border border-border bg-card p-5 md:flex-row md:items-start md:justify-between md:p-6">
        <div className={cn("flex items-center gap-4", dim)}>
          <ImageOrInitials
            path={player.hasPhoto ? playerPhotoPath(player.id) : null}
            name={player.fullName}
            fallbackIcon={User}
            className="size-24 rounded-full bg-secondary text-2xl font-semibold text-secondary-foreground"
            imageClassName="object-cover"
          />
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
        {/* The action area: KAN-59 adds release / re-activate / delete and the photo here. */}
        <div className="flex flex-wrap items-center gap-2">
          {player.active && hasPermission(permissionLevel, "EDIT_FULL") && (
            // A real link styled as a button: Base UI's Button would give the <a> role="button".
            <Link to={editPlayerPath(player.id)} className={buttonVariants({ variant: "outline" })}>
              <Pencil aria-hidden="true" />
              {t("squad.edit")}
            </Link>
          )}
          <BackToSquadLink />
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
    </div>
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
