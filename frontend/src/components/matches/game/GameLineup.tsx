import { useId } from "react";
import { useTranslation } from "react-i18next";
import { LinkedPlayerName } from "@/components/stats/LinkedPlayerName";
import { Badge } from "@/components/ui/badge";
import { APPEARANCE_ROLE_KEYS } from "@/lib/matches/labels";
import { APPEARANCE_ROLES, type GameAppearance } from "@/lib/matches/types";
import { GameEventItem, GameEventsLegend } from "./GameEventIcon";

/**
 * "ההרכב שלנו": our players only (the API has nothing of the opponent's), in three groups in
 * APPEARANCE_ROLES' order (starters, came on, unused), each a list in the response's order (never
 * re-sorted); an empty group isn't shown. No minutes played anywhere: only the subs' minutes, as
 * events.
 */
export function GameLineup({ players }: { players: GameAppearance[] }) {
  const { t } = useTranslation();
  const headingId = useId();
  return (
    <section
      aria-labelledby={headingId}
      className="flex flex-col gap-5 rounded-lg border border-border bg-card px-4 py-4 sm:px-5"
    >
      <h2 id={headingId} className="text-base font-semibold">
        {t("matches.game.lineup")}
      </h2>
      {players.length === 0 ? (
        // Defensive: the contract requires players today; a no-lineup page is KAN-82's.
        <p className="text-sm text-muted-foreground">{t("matches.game.noLineup")}</p>
      ) : (
        <>
          {APPEARANCE_ROLES.map((role) => {
            const group = players.filter((player) => player.role === role);
            if (group.length === 0) {
              return null;
            }
            const groupId = `${headingId}-${role}`;
            return (
              <section key={role} aria-labelledby={groupId} className="flex flex-col gap-1">
                <h3 id={groupId} className="text-sm font-semibold text-muted-foreground">
                  {t("matches.game.groupHeading", {
                    label: t(APPEARANCE_ROLE_KEYS[role]),
                    count: group.length,
                  })}
                </h3>
                <ul aria-labelledby={groupId} className="flex flex-col divide-y divide-border">
                  {group.map((player, index) => (
                    // No ids: the response's order is stable for one answer.
                    <PlayerRow key={index} player={player} />
                  ))}
                </ul>
              </section>
            );
          })}
          <GameEventsLegend className="border-t border-border pt-4" />
        </>
      )}
    </section>
  );
}

/**
 * One player: the shirt number worn in this game (never the squad's jersey number), the name (our
 * player's, linking to the card, when linked), GK / captain, and the events, beside the name from
 * sm up and on their own line below it on a phone.
 */
function PlayerRow({ player }: { player: GameAppearance }) {
  const { t } = useTranslation();
  return (
    <li className="flex flex-col gap-1.5 py-2.5 sm:flex-row sm:items-center sm:gap-4">
      <div className="flex min-w-0 flex-1 flex-wrap items-center gap-x-2.5 gap-y-1">
        <ShirtNumber number={player.shirtNumber} />
        <PlayerName player={player} />
        {player.goalkeeper && (
          <Badge variant="outline" lang="en" dir="ltr">
            GK
          </Badge>
        )}
        {player.captain && <Badge variant="outline">{t("matches.game.captain")}</Badge>}
      </div>
      {player.events.length > 0 && (
        <ul
          aria-label={t("matches.game.events")}
          className="flex flex-wrap items-center gap-x-3 gap-y-1 ps-[2.625rem] sm:ps-0"
        >
          {player.events.map((event, index) => (
            <GameEventItem key={index} event={event} />
          ))}
        </ul>
      )}
    </li>
  );
}

function ShirtNumber({ number }: { number: number | null }) {
  const { t } = useTranslation();
  return (
    <span className="inline-flex size-8 shrink-0 items-center justify-center rounded-full bg-muted text-[0.8125rem] font-semibold tabular-nums">
      <span aria-hidden="true">{number ?? "–"}</span>
      <span className="sr-only">
        {number === null
          ? t("matches.game.shirtNumberUnknown")
          : t("matches.game.shirtNumber", { number })}
      </span>
    </span>
  );
}

/** Linked → our name, linking to the card; else the site's name; a masked one says so. */
function PlayerName({ player }: { player: GameAppearance }) {
  const { t } = useTranslation();
  if (player.linkedPlayer !== null) {
    return <LinkedPlayerName player={player.linkedPlayer} />;
  }
  if (player.name === null) {
    return <span className="text-muted-foreground italic">{t("matches.game.nameHidden")}</span>;
  }
  return <span className="font-medium">{player.name}</span>;
}
