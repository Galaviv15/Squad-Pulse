import { IdCard, Pencil, Trash2, UserCheck, UserMinus, type LucideIcon } from "lucide-react";
import { hasPermission, type PermissionLevel } from "@/lib/auth/permissions";
import { editPlayerPath, playerPath } from "@/lib/squad/paths";
import type { Player } from "@/lib/squad/types";

/** The actions that act in place, each through its confirmation dialog (PlayerActionDialog). */
export type PlayerDialogKind = "release" | "reactivate" | "delete";

interface PlayerActionBase {
  key: string;
  labelKey: string;
  icon: LucideIcon;
}

/** An action that navigates: a link. */
export interface PlayerLinkAction extends PlayerActionBase {
  kind: "link";
  to: string;
}

/** An action that acts in place: it opens its dialog. `destructive` is styled as such. */
export interface PlayerDialogAction extends PlayerActionBase {
  kind: "dialog";
  dialog: PlayerDialogKind;
  destructive: boolean;
}

/** One item of a player's actions (the table's menu, the card's buttons). */
export type PlayerAction = PlayerLinkAction | PlayerDialogAction;

/**
 * The actions `player` offers a user at `permissionLevel`, in menu order, as data:
 * - "open card", always;
 * - "edit" and "release" from EDIT_FULL up, for an active player;
 * - "re-activate" from EDIT_FULL up, for a released one;
 * - "delete permanently", ADMIN only, active or released: last, destructive.
 * Only what the UI shows: the backend checks every request itself.
 */
export function playerActions(player: Player, permissionLevel: PermissionLevel): PlayerAction[] {
  const actions: PlayerAction[] = [
    {
      kind: "link",
      key: "open",
      labelKey: "squad.actions.open",
      icon: IdCard,
      to: playerPath(player.id),
    },
  ];
  if (hasPermission(permissionLevel, "EDIT_FULL")) {
    if (player.active) {
      actions.push(
        {
          kind: "link",
          key: "edit",
          labelKey: "squad.edit",
          icon: Pencil,
          to: editPlayerPath(player.id),
        },
        dialogAction("release", "squad.actions.release", UserMinus),
      );
    } else {
      actions.push(dialogAction("reactivate", "squad.actions.reactivate", UserCheck));
    }
  }
  if (hasPermission(permissionLevel, "ADMIN")) {
    actions.push({ ...dialogAction("delete", "squad.actions.delete", Trash2), destructive: true });
  }
  return actions;
}

function dialogAction(
  dialog: PlayerDialogKind,
  labelKey: string,
  icon: LucideIcon,
): PlayerDialogAction {
  return { kind: "dialog", key: dialog, dialog, labelKey, icon, destructive: false };
}
