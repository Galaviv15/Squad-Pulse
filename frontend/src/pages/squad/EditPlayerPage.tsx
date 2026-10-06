import { useState } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams } from "react-router";
import { PlayerForm } from "@/components/squad/PlayerForm";
import { buttonVariants } from "@/components/ui/button";
import { formValuesToUpdateBody, playerToFormValues } from "@/lib/squad/form";
import { playerPath } from "@/lib/squad/paths";
import { usePlayer, useUpdatePlayer } from "@/lib/squad/players";
import type { Player } from "@/lib/squad/types";
import { PageMessage, PlayerQueryState, RequireEditFull } from "./PlayerPageStates";

/**
 * /app/squad/:playerId/edit: the edit form, from EDIT_FULL up, for an active player (a released
 * one is read-only: a message instead). After a save the card replaces the form in the history.
 * Its title comes from the route.
 */
export function EditPlayerPage() {
  const { playerId = "" } = useParams();
  return (
    <RequireEditFull>
      <EditPlayer key={playerId} playerId={playerId} />
    </RequireEditFull>
  );
}

/** The player the form was filled from, and how many times it has been (re)filled. */
interface FormSource {
  player: Player;
  revision: number;
}

/**
 * The form is filled once, from the first load (`source`), and never from later data: a
 * background refetch (window focus, the invalidation after a write elsewhere) updates the query,
 * not `source`, so it can't overwrite what the user typed. The `version` sent is source's, the
 * one the shown values came from, never the newest in the cache. Only "טעינת הגרסה העדכנית"
 * after a stale-version 409 refills it: a refetch, a new source, and a remount (the revision is
 * the form's key) that drops the user's edits, as its hint says.
 */
function EditPlayer({ playerId }: { playerId: string }) {
  const { t } = useTranslation();
  const player = usePlayer(playerId);
  const update = useUpdatePlayer(playerId);
  const navigate = useNavigate();
  const [source, setSource] = useState<FormSource | null>(null);

  // The first data to arrive fills the form. Set while rendering: React's pattern for state
  // derived once from a value that arrives later.
  if (source === null && player.data !== undefined) {
    setSource({ player: player.data, revision: 0 });
  }

  if (source === null) {
    return <PlayerQueryState query={player} />;
  }
  if (!source.player.active) {
    return (
      <PageMessage>
        <p>{t("squad.form.released")}</p>
        <Link
          to={playerPath(playerId)}
          className={buttonVariants({ variant: "outline", size: "sm" })}
        >
          {t("squad.form.toCard")}
        </Link>
      </PageMessage>
    );
  }

  async function reloadLatest(): Promise<boolean> {
    const result = await player.refetch();
    if (result.isError || result.data === undefined) {
      return false;
    }
    const latest = result.data;
    setSource((previous) => ({ player: latest, revision: (previous?.revision ?? 0) + 1 }));
    return true;
  }

  const { version } = source.player;
  return (
    <PlayerForm
      key={source.revision}
      mode="edit"
      initialValues={playerToFormValues(source.player)}
      save={(values) => update.mutateAsync(formValuesToUpdateBody(values, version))}
      onSaved={() => void navigate(playerPath(playerId), { replace: true })}
      cancelTo={playerPath(playerId)}
      playerId={playerId}
      onReloadLatest={reloadLatest}
    />
  );
}
