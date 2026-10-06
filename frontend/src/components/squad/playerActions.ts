import { IdCard, Pencil, type LucideIcon } from "lucide-react";
import { hasPermission, type PermissionLevel } from "@/lib/auth/permissions";
import { editPlayerPath, playerPath } from "@/lib/squad/paths";
import type { Player } from "@/lib/squad/types";

/**
 * One item of a player's actions menu. Today every item navigates (`to`); KAN-59's release /
 * re-activate / delete add items that act in place, as another kind of this type.
 */
export interface PlayerAction {
  key: string;
  labelKey: string;
  icon: LucideIcon;
  to: string;
}

/**
 * The actions `player` offers a user at `permissionLevel`, in menu order, as data: "open card"
 * always, "edit" from EDIT_FULL up for an active player. Only what the UI shows: the backend
 * checks every request itself.
 */
export function playerActions(player: Player, permissionLevel: PermissionLevel): PlayerAction[] {
  const actions: PlayerAction[] = [
    { key: "open", labelKey: "squad.actions.open", icon: IdCard, to: playerPath(player.id) },
  ];
  if (player.active && hasPermission(permissionLevel, "EDIT_FULL")) {
    actions.push({
      key: "edit",
      labelKey: "squad.edit",
      icon: Pencil,
      to: editPlayerPath(player.id),
    });
  }
  return actions;
}
