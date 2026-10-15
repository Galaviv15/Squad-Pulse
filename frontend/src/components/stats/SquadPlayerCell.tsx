import { EllipsisVertical, Link2, UserPlus } from "lucide-react";
import { useRef, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { ReleasedBadge } from "@/components/squad/PlayerBadges";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import type { PlayerStatsRow } from "@/lib/matches/playerStats";
import { useNewPlayerFromStatsPath } from "@/lib/matches/statsReturn";
import { LinkedPlayerName } from "./LinkedPlayerName";

export interface SquadPlayerCellProps {
  row: PlayerStatsRow;
  /** EDIT_FULL and up: the link controls and the suggestion. Below it, the link only. */
  canEdit: boolean;
  /** A link write for this row is in flight: every button is disabled. */
  pending: boolean;
  /** The last failed write's message (an i18n key), shown under the cell's content. */
  error: string | null;
  /** "אישור": link to the suggested player. */
  onConfirmSuggestion(): void;
  /** Opens the picker ("אחר…", "קישור לשחקן…", "שינוי קישור"); `opener` gets the focus back. */
  onPick(opener: HTMLElement | null): void;
  /** "ביטול קישור": unlinks at once, no confirmation (cheap and reversible). */
  onUnlink(): void;
}

/**
 * The stats table's "שחקן בסגל" cell. Below EDIT_FULL: the linked squad player as a link to the
 * card, or a muted "—" ("לא מקושר"); never a suggestion or an action. From EDIT_FULL up, by the
 * row: linked → the name, the released badge and a "⋯" menu (change, unlink); a suggestion → the
 * dashed chip with "אישור" and "אחר…"; neither → "קישור לשחקן…" and (KAN-77) "יצירת שחקן בסגל",
 * a link to the create-player form for this row (navigation, so a link, styled as a button). The
 * backend's suggestion is only a suggestion: nothing links without a click on "אישור".
 */
export function SquadPlayerCell({
  row,
  canEdit,
  pending,
  error,
  onConfirmSuggestion,
  onPick,
  onUnlink,
}: SquadPlayerCellProps) {
  const { t } = useTranslation();
  const newPlayerPath = useNewPlayerFromStatsPath();
  const { linkedPlayer, suggestedPlayer } = row;

  let content;
  if (linkedPlayer !== null) {
    content = (
      <div className="flex items-center gap-2">
        {canEdit && <Link2 aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />}
        <LinkedPlayerName player={linkedPlayer} />
        {canEdit && (
          <LinkActionsMenu row={row} pending={pending} onPick={onPick} onUnlink={onUnlink} />
        )}
      </div>
    );
  } else if (!canEdit) {
    content = (
      <span className="text-muted-foreground">
        <span aria-hidden="true">—</span>
        <span className="sr-only">{t("stats.notLinked")}</span>
      </span>
    );
  } else if (suggestedPlayer !== null) {
    content = (
      <div className="flex items-center gap-2">
        <span className="inline-flex items-center gap-1.5 rounded-full border border-dashed border-input px-2.5 py-0.5 text-[0.8125rem]">
          {t("stats.suggestion", { name: suggestedPlayer.fullName })}
          {!suggestedPlayer.active && <ReleasedBadge />}
        </span>
        <UnlinkedActions>
          {/* Named with the scraped player, so 34 "אישור" buttons can be told apart. */}
          <Button
            size="sm"
            disabled={pending}
            aria-label={t("stats.confirmLabel", { name: row.name })}
            onClick={onConfirmSuggestion}
          >
            {t("stats.confirm")}
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={pending}
            aria-label={t("stats.otherLabel", { name: row.name })}
            onClick={(event) => onPick(event.currentTarget)}
          >
            {t("stats.other")}
          </Button>
        </UnlinkedActions>
      </div>
    );
  } else {
    content = (
      <UnlinkedActions>
        <Button
          size="sm"
          variant="outline"
          disabled={pending}
          aria-label={t("stats.linkToLabel", { name: row.name })}
          onClick={(event) => onPick(event.currentTarget)}
        >
          <Link2 aria-hidden="true" />
          {t("stats.linkTo")}
        </Button>
        {/* Named with the scraped player too: one per unlinked row. */}
        <Link
          to={newPlayerPath(row.id)}
          aria-label={t("stats.createPlayer.actionLabel", { name: row.name })}
          className={buttonVariants({ variant: "outline", size: "sm" })}
        >
          <UserPlus aria-hidden="true" />
          {t("stats.createPlayer.action")}
        </Link>
      </UnlinkedActions>
    );
  }

  return (
    <div className="flex flex-col gap-1">
      {content}
      {error !== null && (
        <p role="alert" className="max-w-72 text-[0.8125rem] whitespace-normal text-danger">
          {t(error)}
        </p>
      )}
    </div>
  );
}

function UnlinkedActions({ children }: { children: ReactNode }) {
  return <div className="flex items-center gap-1.5">{children}</div>;
}

type LinkAction = "change" | "unlink";

/**
 * A linked row's "⋯" menu, the PlayerActionsMenu pattern: a Base UI menu portaled to the body; an
 * item only records its choice, acted on once the menu has finished closing (its focus back on the
 * trigger), so the picker's focus handling never races the menu's.
 */
function LinkActionsMenu({
  row,
  pending,
  onPick,
  onUnlink,
}: Pick<SquadPlayerCellProps, "row" | "pending" | "onPick" | "onUnlink">) {
  const { t } = useTranslation();
  const trigger = useRef<HTMLButtonElement>(null);
  const chosen = useRef<LinkAction | null>(null);

  return (
    <DropdownMenu
      onOpenChange={(open) => {
        if (open) {
          chosen.current = null;
        }
      }}
      onOpenChangeComplete={(open) => {
        const action = chosen.current;
        if (open || action === null) {
          return;
        }
        chosen.current = null;
        if (action === "change") {
          onPick(trigger.current);
        } else {
          onUnlink();
        }
      }}
    >
      <DropdownMenuTrigger
        ref={trigger}
        disabled={pending}
        render={<Button variant="ghost" size="icon-sm" />}
        aria-label={t("stats.menu", { name: row.name })}
      >
        <EllipsisVertical aria-hidden="true" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-auto min-w-36">
        <DropdownMenuItem onClick={() => (chosen.current = "change")}>
          {t("stats.change")}
        </DropdownMenuItem>
        <DropdownMenuItem onClick={() => (chosen.current = "unlink")}>
          {t("stats.unlink")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
