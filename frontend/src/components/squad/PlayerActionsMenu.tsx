import { EllipsisVertical } from "lucide-react";
import { Fragment, useRef } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLinkItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { Player } from "@/lib/squad/types";
import type { PlayerAction, PlayerDialogKind } from "./playerActions";

/**
 * A row's actions: a ghost icon button ("פעולות עבור <name>") opening a Base UI menu, portaled to
 * the body. React still bubbles its clicks to the row through the portal, so the row's own click
 * handler must ignore anything that isn't inside the row in the DOM (see SquadTable); the same
 * goes for the dialogs the in-place items open.
 *
 * A link item navigates. An in-place item (release, re-activate, delete) only records its choice;
 * `onDialog` gets it once the menu has finished closing (its focus back on the trigger), so the
 * menu's focus handling never races the dialog's. The host renders the dialog, outside the menu,
 * and gets the trigger to return focus to. A destructive item comes after a separator.
 */
export function PlayerActionsMenu({
  player,
  actions,
  onDialog,
}: {
  player: Player;
  actions: PlayerAction[];
  onDialog(kind: PlayerDialogKind, player: Player, trigger: HTMLElement | null): void;
}) {
  const { t } = useTranslation();
  const trigger = useRef<HTMLButtonElement>(null);
  const chosen = useRef<PlayerDialogKind | null>(null);

  return (
    <DropdownMenu
      onOpenChange={(open) => {
        if (open) {
          chosen.current = null;
        }
      }}
      onOpenChangeComplete={(open) => {
        const kind = chosen.current;
        if (!open && kind !== null) {
          chosen.current = null;
          onDialog(kind, player, trigger.current);
        }
      }}
    >
      <DropdownMenuTrigger
        ref={trigger}
        render={<Button variant="ghost" size="icon-sm" />}
        aria-label={t("squad.actions.menu", { name: player.fullName })}
      >
        <EllipsisVertical aria-hidden="true" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-auto min-w-40">
        {actions.map((action) => {
          const { key, labelKey, icon: Icon } = action;
          if (action.kind === "link") {
            return (
              <DropdownMenuLinkItem key={key} closeOnClick render={<Link to={action.to} />}>
                <Icon aria-hidden="true" />
                {t(labelKey)}
              </DropdownMenuLinkItem>
            );
          }
          return (
            <Fragment key={key}>
              {action.destructive && <DropdownMenuSeparator />}
              <DropdownMenuItem
                variant={action.destructive ? "destructive" : "default"}
                onClick={() => {
                  chosen.current = action.dialog;
                }}
              >
                <Icon aria-hidden="true" />
                {t(labelKey)}
              </DropdownMenuItem>
            </Fragment>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
