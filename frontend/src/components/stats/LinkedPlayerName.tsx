import { Link } from "react-router";
import { ReleasedBadge } from "@/components/squad/PlayerBadges";
import type { StatsPlayerRef } from "@/lib/matches/playerStats";
import { playerPath } from "@/lib/squad/paths";

/**
 * A linked squad player's name (KAN-72 links) as a link to their card, with the badge if released:
 * the stats table's cell (KAN-74) and the game page's lineup (KAN-81). Always our name, never the
 * scraped one.
 */
export function LinkedPlayerName({ player }: { player: StatsPlayerRef }) {
  return (
    <>
      <Link
        to={playerPath(player.id)}
        className="rounded-sm font-medium outline-none hover:underline focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        {player.fullName}
      </Link>
      {!player.active && <ReleasedBadge />}
    </>
  );
}
