import { useNavigate } from "react-router";
import { PlayerForm } from "@/components/squad/PlayerForm";
import { EMPTY_PLAYER_FORM, formValuesToCreateBody } from "@/lib/squad/form";
import { playerPath, SQUAD_PATH } from "@/lib/squad/paths";
import { useCreatePlayer } from "@/lib/squad/players";
import { RequireEditFull } from "./PlayerPageStates";

/**
 * /app/squad/new: the add-player form, from EDIT_FULL up. A saved player's card replaces the
 * form in the history, so Back doesn't return to it. Its title comes from the route.
 */
export function NewPlayerPage() {
  return (
    <RequireEditFull>
      <NewPlayerForm />
    </RequireEditFull>
  );
}

function NewPlayerForm() {
  const create = useCreatePlayer();
  const navigate = useNavigate();

  return (
    <PlayerForm
      mode="create"
      initialValues={EMPTY_PLAYER_FORM}
      save={(values) => create.mutateAsync(formValuesToCreateBody(values))}
      onSaved={(player) => void navigate(playerPath(player.id), { replace: true })}
      cancelTo={SQUAD_PATH}
    />
  );
}
