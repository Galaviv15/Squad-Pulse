import { User } from "lucide-react";
import type { MouseEvent } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate } from "react-router";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import { Badge } from "@/components/ui/badge";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { ageOn } from "@/lib/squad/age";
import { MEDICAL_STATUS_KEYS, PREFERRED_FOOT_KEYS } from "@/lib/squad/labels";
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

/** A missing value. */
function None() {
  return <span className="text-muted-foreground">—</span>;
}

/**
 * The squad table: one row per player, in the order given (the server's squad order: never
 * re-sorted here). The name is a link to the player's card, the keyboard path; a click anywhere
 * else on the row opens it too, as a mouse convenience. A released player's row is dimmed and
 * carries a "משוחרר" pill, so it isn't marked by color alone.
 */
export function SquadTable({ players }: { players: Player[] }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const today = new Date();

  function openFromRow(event: MouseEvent<HTMLTableRowElement>, player: Player) {
    // The link navigates by itself, and a click that ends a text selection isn't a "open" click.
    if (event.target instanceof Element && event.target.closest("a")) {
      return;
    }
    if ((window.getSelection()?.toString() ?? "") !== "") {
      return;
    }
    void navigate(playerPath(player.id));
  }

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
          {/* KAN-50 adds the row-actions column (edit, release, delete) here. */}
        </TableRow>
      </TableHeader>
      <TableBody>
        {players.map((player) => {
          const age = ageOn(player.dateOfBirth, today);
          return (
            <TableRow
              key={player.id}
              data-released={!player.active || undefined}
              className="h-[52px] cursor-pointer data-released:[&>td]:opacity-60"
              onClick={(event) => openFromRow(event, player)}
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
                  {!player.active && <Badge variant="muted">{t("squad.released")}</Badge>}
                </div>
              </TableCell>
              <TableCell className="px-4">
                {player.primaryPosition === null && player.secondaryPosition === null ? (
                  <None />
                ) : (
                  <div className="flex items-center gap-1.5">
                    {player.primaryPosition !== null && (
                      <Badge dir="ltr">{player.primaryPosition}</Badge>
                    )}
                    {player.secondaryPosition !== null && (
                      <Badge variant="outline" dir="ltr">
                        {player.secondaryPosition}
                      </Badge>
                    )}
                  </div>
                )}
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
                <Badge variant={player.medicalStatus === "INJURED" ? "danger" : "success"}>
                  {t(MEDICAL_STATUS_KEYS[player.medicalStatus])}
                </Badge>
              </TableCell>
            </TableRow>
          );
        })}
      </TableBody>
    </Table>
  );
}
