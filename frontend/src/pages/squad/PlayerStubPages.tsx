import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { buttonVariants } from "@/components/ui/button";
import { SQUAD_PATH } from "@/lib/squad/paths";

/**
 * /app/squad/new: a stub until the player form (KAN-50), which must also limit it to EDIT_FULL
 * and up. Its title comes from the route.
 */
export function NewPlayerPage() {
  return <SquadStub textKey="squad.newPlayerStub" />;
}

/**
 * /app/squad/:playerId: a stub until the player card (KAN-50). It doesn't load the player. Its
 * title comes from the route.
 */
export function PlayerCardPage() {
  return <SquadStub textKey="squad.playerCardStub" />;
}

function SquadStub({ textKey }: { textKey: string }) {
  const { t } = useTranslation();

  return (
    <div className="flex flex-col items-start gap-4">
      <p className="text-sm text-muted-foreground">{t(textKey)}</p>
      <Link to={SQUAD_PATH} className={buttonVariants({ variant: "outline", size: "sm" })}>
        {t("squad.backToSquad")}
      </Link>
    </div>
  );
}
