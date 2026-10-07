import { User } from "lucide-react";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { ageOn } from "@/lib/squad/age";
import { PREFERRED_FOOT_KEYS } from "@/lib/squad/labels";
import { playerPath, playerPhotoPath } from "@/lib/squad/paths";
import type { Player } from "@/lib/squad/types";
import { cn } from "@/lib/utils";
import { isOpenClick } from "./openClick";
import { MedicalStatusBadge, None, PositionChips, ReleasedBadge } from "./PlayerBadges";
import { PlayerActionsMenu } from "./PlayerActionsMenu";
import { playerActions, type PlayerDialogKind } from "./playerActions";

/**
 * The squad as a grid of player cards: the same list as the table (SquadTable), in the order
 * given (the server's: never re-sorted here), as a list, so a screen reader hears how many. Each
 * card: the avatar, the name (a link to the player's card, the keyboard path), the position chips
 * and the jersey number; age, height, weight and foot; the medical pill (and "משוחרר" for a
 * released player) and the same actions menu as a table row. A click anywhere else on the card
 * opens the player too (isOpenClick, shared with the table). A released player's card is dimmed,
 * all but its actions menu. `busy` (a new filter's list loading) dims the grid and marks it
 * aria-busy, as the table's frame is.
 */
export function SquadCards({
  players,
  busy,
  onDialog,
}: {
  players: Player[];
  busy: boolean;
  onDialog(kind: PlayerDialogKind, player: Player, trigger: HTMLElement | null): void;
}) {
  const today = new Date();

  return (
    <ul
      aria-busy={busy || undefined}
      className={cn(
        "grid grid-cols-[repeat(auto-fill,minmax(min(232px,100%),1fr))] gap-4 transition-opacity",
        busy && "opacity-60",
      )}
    >
      {players.map((player) => (
        <PlayerCard key={player.id} player={player} today={today} onDialog={onDialog} />
      ))}
    </ul>
  );
}

function PlayerCard({
  player,
  today,
  onDialog,
}: {
  player: Player;
  today: Date;
  onDialog(kind: PlayerDialogKind, player: Player, trigger: HTMLElement | null): void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { permissionLevel } = useCurrentUser();
  const age = ageOn(player.dateOfBirth, today);
  const dimmed = !player.active && "opacity-60";

  return (
    <li
      data-released={!player.active || undefined}
      className="flex cursor-pointer flex-col gap-3.5 rounded-lg border border-border bg-card p-4 transition-colors hover:border-input"
      onClick={(event) => {
        if (isOpenClick(event)) {
          void navigate(playerPath(player.id));
        }
      }}
    >
      <div className={cn("flex items-start gap-3", dimmed)}>
        <ImageOrInitials
          path={player.hasPhoto ? playerPhotoPath(player.id) : null}
          name={player.fullName}
          fallbackIcon={User}
          className="size-14 rounded-full bg-secondary text-lg font-semibold text-secondary-foreground"
          imageClassName="object-cover"
        />
        <div className="flex min-w-0 flex-1 flex-col items-start gap-1">
          <Link
            to={playerPath(player.id)}
            className="max-w-full truncate rounded-sm text-[0.9375rem] font-semibold outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
          >
            {player.fullName}
          </Link>
          <PositionChips
            primary={player.primaryPosition}
            secondary={player.secondaryPosition}
            className="flex-wrap"
          />
        </div>
        {player.jerseyNumber === null ? (
          <None />
        ) : (
          <span
            dir="ltr"
            className="shrink-0 text-[1.625rem] leading-none font-bold text-primary tabular-nums"
          >
            #{player.jerseyNumber}
          </span>
        )}
      </div>
      <dl
        className={cn("grid grid-cols-4 gap-1 border-y border-border py-2.5 text-center", dimmed)}
      >
        <Stat term={t("squad.columns.age")}>{age ?? <None />}</Stat>
        <Stat term={t("squad.stats.height")}>{player.heightCm ?? <None />}</Stat>
        <Stat term={t("squad.stats.weight")}>{player.weightKg ?? <None />}</Stat>
        <Stat term={t("squad.columns.foot")}>
          {player.preferredFoot === null ? <None /> : t(PREFERRED_FOOT_KEYS[player.preferredFoot])}
        </Stat>
      </dl>
      <div className="flex items-center justify-between gap-2">
        <div className={cn("flex flex-wrap items-center gap-1.5", dimmed)}>
          <MedicalStatusBadge status={player.medicalStatus} />
          {!player.active && <ReleasedBadge />}
        </div>
        <PlayerActionsMenu
          player={player}
          actions={playerActions(player, permissionLevel)}
          onDialog={onDialog}
        />
      </div>
    </li>
  );
}

/** One of a card's stats: the term (12 muted) over the value (14/600). */
function Stat({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-0.5">
      <dt className="text-xs text-muted-foreground">{term}</dt>
      <dd className="text-sm font-semibold tabular-nums">{children}</dd>
    </div>
  );
}
