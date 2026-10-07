import { User } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import { isOpenClick } from "./openClick";
import { MedicalStatusBadge, None, PositionChips, ReleasedBadge } from "./PlayerBadges";
import { PlayerActionsMenu } from "./PlayerActionsMenu";
import { playerActions, type PlayerDialogKind } from "./playerActions";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ageOn } from "@/lib/squad/age";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { PREFERRED_FOOT_KEYS } from "@/lib/squad/labels";
import { playerPath, playerPhotoPath } from "@/lib/squad/paths";
import type { Player } from "@/lib/squad/types";

const COLUMN_KEYS = [
  "jerseyNumber",
  "name",
  "position",
  "age",
  "height",
  "weight",
  "foot",
  "medicalStatus",
] as const;

/**
 * The squad table: one row per player, in the order given (the server's squad order: never
 * re-sorted here). The name is a link to the player's card, the keyboard path; a click anywhere
 * else on the row opens it too, as a mouse convenience (isOpenClick, shared with the cards). A
 * released player's row is dimmed and carries a "משוחרר" pill, so it isn't marked by color alone.
 * The last column is the row's actions menu (playerActions), never dimmed; its in-place items go
 * to `onDialog`, whose host (the squad page) renders the dialog, so it outlives the table (e.g.
 * when the list empties).
 */
export function SquadTable({
  players,
  onDialog,
}: {
  players: Player[];
  onDialog(kind: PlayerDialogKind, player: Player, trigger: HTMLElement | null): void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { permissionLevel } = useCurrentUser();
  const today = new Date();

  return (
    <Table>
      <TableHeader>
        <TableRow className="bg-muted/50 hover:bg-muted/50">
          {COLUMN_KEYS.map((key) => (
            <TableHead
              key={key}
              scope="col"
              className="h-11 px-4 text-[0.8125rem] font-medium text-muted-foreground"
            >
              {t(`squad.columns.${key}`)}
            </TableHead>
          ))}
          <TableHead scope="col" className="h-11 w-14 px-4 text-end">
            <span className="sr-only">{t("squad.actions.column")}</span>
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {players.map((player) => {
          const age = ageOn(player.dateOfBirth, today);
          return (
            <TableRow
              key={player.id}
              data-released={!player.active || undefined}
              className="h-[52px] cursor-pointer data-released:[&>td:not([data-actions])]:opacity-60"
              onClick={(event) => {
                if (isOpenClick(event)) {
                  void navigate(playerPath(player.id));
                }
              }}
            >
              <TableCell className="px-4 font-semibold tabular-nums">
                {player.jerseyNumber === null ? (
                  <None />
                ) : (
                  <span dir="ltr">{player.jerseyNumber}</span>
                )}
              </TableCell>
              <TableCell className="px-4">
                <div className="flex items-center gap-2.5">
                  <ImageOrInitials
                    path={player.hasPhoto ? playerPhotoPath(player.id) : null}
                    name={player.fullName}
                    fallbackIcon={User}
                    className="size-8 rounded-full bg-secondary text-xs font-semibold text-secondary-foreground"
                    imageClassName="object-cover"
                  />
                  <Link
                    to={playerPath(player.id)}
                    className="rounded-sm font-semibold outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
                  >
                    {player.fullName}
                  </Link>
                  {!player.active && <ReleasedBadge />}
                </div>
              </TableCell>
              <TableCell className="px-4">
                <PositionChips
                  primary={player.primaryPosition}
                  secondary={player.secondaryPosition}
                />
              </TableCell>
              <TableCell className="px-4 tabular-nums">{age === null ? <None /> : age}</TableCell>
              <TableCell className="px-4 tabular-nums">
                {player.heightCm === null ? <None /> : player.heightCm}
              </TableCell>
              <TableCell className="px-4 tabular-nums">
                {player.weightKg === null ? <None /> : player.weightKg}
              </TableCell>
              <TableCell className="px-4">
                {player.preferredFoot === null ? (
                  <None />
                ) : (
                  t(PREFERRED_FOOT_KEYS[player.preferredFoot])
                )}
              </TableCell>
              <TableCell className="px-4">
                <MedicalStatusBadge status={player.medicalStatus} />
              </TableCell>
              <TableCell data-actions="" className="px-4 text-end">
                <PlayerActionsMenu
                  player={player}
                  actions={playerActions(player, permissionLevel)}
                  onDialog={onDialog}
                />
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
