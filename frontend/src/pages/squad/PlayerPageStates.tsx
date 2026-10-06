import type { UseQueryResult } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { Button, buttonVariants } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { hasPermission } from "@/lib/auth/permissions";
import { SQUAD_PATH } from "@/lib/squad/paths";
import type { Player } from "@/lib/squad/types";

/** A page-level message in a card panel: a state instead of the page's content. */
export function PageMessage({ children }: { children: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-lg border border-border bg-card px-4 py-12 text-center text-sm text-muted-foreground">
      {children}
    </div>
  );
}

/** "חזרה לסגל", as an outline button-styled link: 36px, or 40px beside other buttons (the card). */
export function BackToSquadLink({ size = "sm" }: { size?: "sm" | "default" }) {
  const { t } = useTranslation();
  return (
    <Link to={SQUAD_PATH} className={buttonVariants({ variant: "outline", size })}>
      {t("squad.backToSquad")}
    </Link>
  );
}

/**
 * Renders its children (the add / edit form) only from EDIT_FULL up; anyone else gets the
 * "no permission" message in the page instead, and nothing below is mounted, so nothing is
 * fetched. Only what the UI shows: the backend checks every write itself.
 */
export function RequireEditFull({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const { permissionLevel } = useCurrentUser();
  if (!hasPermission(permissionLevel, "EDIT_FULL")) {
    return (
      <PageMessage>
        <p>{t("squad.noPermission")}</p>
        <BackToSquadLink />
      </PageMessage>
    );
  }
  return children;
}

/**
 * What a player page shows while its player has no data: loading, "not found" (a 404: missing, or
 * another club's), or the load error with a retry.
 */
export function PlayerQueryState({ query }: { query: UseQueryResult<Player> }) {
  const { t } = useTranslation();
  if (!query.isError || query.isFetching) {
    return (
      <PageMessage>
        <p role="status">{t("squad.player.loading")}</p>
      </PageMessage>
    );
  }
  if (query.error instanceof ApiError && query.error.status === 404) {
    return (
      <PageMessage>
        <p>{t("squad.player.notFound")}</p>
        <BackToSquadLink />
      </PageMessage>
    );
  }
  return (
    <PageMessage>
      <p>{t("squad.player.loadError")}</p>
      <Button variant="outline" size="sm" onClick={() => void query.refetch()}>
        {t("squad.retry")}
      </Button>
    </PageMessage>
  );
}
