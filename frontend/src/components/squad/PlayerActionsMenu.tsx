import { EllipsisVertical } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLinkItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { Player } from "@/lib/squad/types";
import type { PlayerAction } from "./playerActions";

/**
 * A row's actions: a ghost icon button ("פעולות עבור <name>") opening a Base UI menu, portaled to
 * the body. React still bubbles its clicks to the row through the portal, so the row's own click
 * handler must ignore anything that isn't inside the row in the DOM (see SquadTable).
 */
export function PlayerActionsMenu({
  player,
  actions,
}: {
  player: Player;
  actions: PlayerAction[];
}) {
  const { t } = useTranslation();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="ghost" size="icon-sm" />}
        aria-label={t("squad.actions.menu", { name: player.fullName })}
      >
        <EllipsisVertical aria-hidden="true" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-auto min-w-40">
        {actions.map(({ key, labelKey, icon: Icon, to }) => (
          <DropdownMenuLinkItem key={key} closeOnClick render={<Link to={to} />}>
            <Icon aria-hidden="true" />
            {t(labelKey)}
          </DropdownMenuLinkItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
