import { useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Link, useNavigate, useParams, useSearchParams } from "react-router";
import { FormAlert } from "@/components/form/FormMessage";
import { QueryContent } from "@/components/matches/MatchDataStates";
import { PlayerForm } from "@/components/squad/PlayerForm";
import { buttonVariants } from "@/components/ui/button";
import { ApiError } from "@/lib/api/errors";
import {
  useCreatePlayerFromStats,
  usePlayerStats,
  type PlayerStatsRow,
  type StatsPlayerRef,
} from "@/lib/matches/playerStats";
import { PLAYER_STATS_ERROR_CODES } from "@/lib/matches/playerStatsErrorCodes";
import { parseStatsFilter, type StatsFilter } from "@/lib/matches/statsFilter";
import { statsPath, statsReturnState } from "@/lib/matches/statsReturn";
import { EMPTY_PLAYER_FORM, formValuesToCreateBody } from "@/lib/squad/form";
import { playerPath } from "@/lib/squad/paths";
import { PageMessage, RequireEditFull } from "@/pages/squad/PlayerPageStates";

/**
 * /app/stats/:id/new-player (KAN-77): the normal add-player form for a scraped row that isn't
 * linked yet, its name pre-filled; saving creates the squad player and links the row to it in one
 * request (POST /matches/player-stats/{id}/player). From EDIT_FULL up (RequireEditFull: below it
 * nothing here is mounted, so the stats aren't requested). Its title comes from the route.
 *
 * The row comes from the stats query the stats screen uses (the same cache). The way back keeps
 * the screen's filter, taken from this page's own `filter` parameter through parseStatsFilter
 * only (see statsReturn.ts), and focuses the row.
 */
export function NewPlayerFromStatsPage() {
  return (
    <RequireEditFull>
      <NewPlayerFromStats />
    </RequireEditFull>
  );
}

function NewPlayerFromStats() {
  const { t } = useTranslation();
  const { id = "" } = useParams();
  const [searchParams] = useSearchParams();
  const filter = parseStatsFilter(searchParams);
  const stats = usePlayerStats();
  // Once the form is shown it stays, whatever a background refetch of the stats says: it must
  // never be unmounted (or remounted) under the user's typing. A row linked or gone meanwhile is
  // the save's 409 / 404 instead.
  const [formRow, setFormRow] = useState<PlayerStatsRow | null>(null);

  const data = stats.data;
  const row = data?.state === "OK" ? data.players.find((each) => each.id === id) : undefined;
  if (formRow === null && row !== undefined && row.linkedPlayer === null) {
    setFormRow(row); // this component's own state while rendering: React re-renders at once
  }

  if (formRow !== null) {
    return <CreateForm row={formRow} filter={filter} />;
  }
  return (
    <QueryContent query={stats} errorMessage={t("stats.loadError")}>
      {() =>
        row?.linkedPlayer != null ? (
          <AlreadyLinked player={row.linkedPlayer} filter={filter} rowId={row.id} />
        ) : (
          <PageMessage>
            <p>{t("stats.createPlayer.rowGone")}</p>
            <BackToStatsLink filter={filter} rowId={null} />
          </PageMessage>
        )
      }
    </QueryContent>
  );
}

function AlreadyLinked({
  player,
  filter,
  rowId,
}: {
  player: StatsPlayerRef;
  filter: StatsFilter;
  rowId: string;
}) {
  const { t } = useTranslation();
  return (
    <PageMessage>
      <p>{t("stats.createPlayer.alreadyLinked", { name: player.fullName })}</p>
      <div className="flex flex-wrap justify-center gap-2">
        <Link
          to={playerPath(player.id)}
          className={buttonVariants({ variant: "outline", size: "sm" })}
        >
          {t("squad.form.toCard")}
        </Link>
        <BackToStatsLink filter={filter} rowId={rowId} />
      </div>
    </PageMessage>
  );
}

/** "חזרה לסטטיסטיקה": the stats screen with its filter, focusing the row if there is one. */
function BackToStatsLink({
  filter,
  rowId,
  variant = "button",
}: {
  filter: StatsFilter;
  rowId: string | null;
  variant?: "button" | "inline";
}) {
  const { t } = useTranslation();
  return (
    <Link
      to={statsPath(filter)}
      state={rowId === null ? undefined : statsReturnState(rowId)}
      className={
        variant === "inline"
          ? "w-fit underline underline-offset-4 hover:no-underline"
          : buttonVariants({ variant: "outline", size: "sm" })
      }
    >
      {t("stats.createPlayer.backToStats")}
    </Link>
  );
}

function CreateForm({ row, filter }: { row: PlayerStatsRow; filter: StatsFilter }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const create = useCreatePlayerFromStats();
  // The initial values once: PlayerForm ignores later changes anyway, and nothing keys it.
  const [initialValues] = useState(() => ({ ...EMPTY_PLAYER_FORM, fullName: row.name }));

  /** This endpoint's own outcomes; everything else is mapped as on /app/squad/new. */
  const saveErrorAlert = (error: unknown): ReactNode | null => {
    if (!(error instanceof ApiError)) {
      return null;
    }
    const back = <BackToStatsLink filter={filter} rowId={row.id} variant="inline" />;
    if (error.status === 404) {
      return <FormAlert action={back}>{t("stats.createPlayer.errors.notFound")}</FormAlert>;
    }
    if (error.status === 409 && error.code === PLAYER_STATS_ERROR_CODES.STATS_ROW_ALREADY_LINKED) {
      return <FormAlert action={back}>{t("stats.createPlayer.errors.alreadyLinked")}</FormAlert>;
    }
    if (error.status === 409 && error.code === PLAYER_STATS_ERROR_CODES.CONCURRENT_MODIFICATION) {
      return <FormAlert>{t("stats.createPlayer.errors.concurrent")}</FormAlert>;
    }
    return null;
  };

  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-col gap-1 rounded-lg border border-border bg-card px-5 py-4">
        <p className="font-medium">{t("stats.createPlayer.fromSite", { name: row.name })}</p>
        <p className="text-[0.8125rem] text-muted-foreground">{t("stats.createPlayer.nameHint")}</p>
      </div>
      <PlayerForm
        mode="create"
        initialValues={initialValues}
        save={(values) =>
          create.mutateAsync({ rowId: row.id, body: formValuesToCreateBody(values) })
        }
        onSaved={(player) =>
          void navigate(statsPath(filter), {
            replace: true,
            state: statsReturnState(row.id, player.fullName),
          })
        }
        cancelTo={statsPath(filter)}
        cancelState={statsReturnState(row.id)}
        saveErrorAlert={saveErrorAlert}
      />
    </div>
  );
}
