import { useQuery } from "@tanstack/react-query";
import { Search, User } from "lucide-react";
import { useId, useMemo, useState } from "react";
import { useTranslation } from "react-i18next";
import { FormAlert } from "@/components/form/FormMessage";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import { MatchDataError } from "@/components/matches/MatchDataStates";
import { ActionDialog } from "@/components/squad/ActionDialog";
import { ReleasedBadge } from "@/components/squad/PlayerBadges";
import type { FinalFocus } from "@/components/squad/playerDialogs";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import { linkErrorKey, useLinkStatsPlayer, type PlayerStatsRow } from "@/lib/matches/playerStats";
import { squadPlayersQuery } from "@/lib/squad/players";
import type { Player } from "@/lib/squad/types";

/** Every squad player, released ones included: the same query (and cache) as the squad's list. */
const ALL_PLAYERS = squadPlayersQuery({ status: "all" });

/** Case- and whitespace-insensitive: "dani le" matches "Dani  Levi". */
function normalize(text: string) {
  return text.toLocaleLowerCase().replace(/\s+/g, "");
}

export interface LinkPickerDialogProps {
  /** The scraped row to link. */
  row: PlayerStatsRow;
  /** Every row of the current stats: who is linked to which row already. */
  rows: PlayerStatsRow[];
  open: boolean;
  onClose(): void;
  onClosed?(): void;
  finalFocus?: FinalFocus;
  /** After a successful PUT, before closing: the list is being refetched. */
  onLinked(): void;
}

/**
 * The link / change-link picker for one scraped row: a search over the squad (client-side) and a
 * single-choice list of real radio inputs, in the server's squad order. Mounted only when opened,
 * so GET /squad/players is never requested before. A player linked to another row of the current
 * stats is shown disabled with that row's name; the row's own linked player starts selected.
 *
 * The disabled options come from the stats shown, which can't know a player linked to a scraped
 * player absent from the current snapshot (a link survives a player's absence from a scrape), so
 * a PUT can still answer 409 PLAYER_ALREADY_LINKED: shown here, and the list is refetched.
 */
export function LinkPickerDialog({ row, rows, onLinked, ...dialog }: LinkPickerDialogProps) {
  const { t } = useTranslation();
  const squad = useQuery(ALL_PLAYERS);
  const link = useLinkStatsPlayer();
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<string | null>(row.linkedPlayer?.id ?? null);
  const searchId = useId();
  const listName = useId();

  // A squad player's id → the name of the other row it's linked to.
  const linkedElsewhere = useMemo(() => {
    const map = new Map<string, string>();
    for (const other of rows) {
      if (other.id !== row.id && other.linkedPlayer !== null) {
        map.set(other.linkedPlayer.id, other.name);
      }
    }
    return map;
  }, [rows, row.id]);

  const players = squad.data;
  const query = normalize(search);
  const shown =
    players === undefined
      ? []
      : query === ""
        ? players
        : players.filter((player) => normalize(player.fullName).includes(query));

  let list;
  if (players === undefined) {
    list = squad.isError ? (
      <MatchDataError message={t("stats.picker.loadError")} onRetry={() => void squad.refetch()} />
    ) : (
      <p role="status" className="text-sm text-muted-foreground">
        {t("stats.picker.loading")}
      </p>
    );
  } else if (players.length === 0) {
    list = <p className="text-sm text-muted-foreground">{t("stats.picker.empty")}</p>;
  } else {
    list = (
      <fieldset className="min-w-0">
        <legend className="sr-only">{t("stats.picker.listLabel")}</legend>
        {shown.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t("stats.picker.noMatch")}</p>
        ) : (
          <ul role="list" className="flex max-h-72 flex-col gap-1 overflow-y-auto">
            {shown.map((player) => (
              <PlayerOption
                key={player.id}
                player={player}
                name={listName}
                checked={selected === player.id}
                linkedTo={linkedElsewhere.get(player.id) ?? null}
                disabled={link.isPending}
                onSelect={() => setSelected(player.id)}
              />
            ))}
          </ul>
        )}
      </fieldset>
    );
  }

  return (
    <ActionDialog
      {...dialog}
      pending={link.isPending}
      confirmDisabled={selected === null}
      title={t("stats.picker.title", { name: row.name })}
      description={t("stats.picker.text")}
      confirmLabel={t("stats.picker.confirm")}
      pendingLabel={t("stats.picker.pending")}
      error={link.error && <FormAlert>{t(linkErrorKey(link.error))}</FormAlert>}
      onConfirm={async () => {
        if (selected === null) {
          return;
        }
        try {
          await link.mutateAsync({ rowId: row.id, playerId: selected });
        } catch {
          return; // Shown from link.error.
        }
        onLinked();
        dialog.onClose();
      }}
    >
      <div className="flex flex-col gap-1.5">
        <label htmlFor={searchId} className="text-[0.8125rem] font-medium">
          {t("stats.picker.search")}
        </label>
        <div className="relative">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute start-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
          />
          <Input
            id={searchId}
            type="search"
            autoComplete="off"
            className="ps-9"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        </div>
      </div>
      {list}
    </ActionDialog>
  );
}

/** One squad player as a radio option: initials (never a photo request), name, position, number. */
function PlayerOption({
  player,
  name,
  checked,
  linkedTo,
  disabled,
  onSelect,
}: {
  player: Player;
  name: string;
  checked: boolean;
  linkedTo: string | null;
  disabled: boolean;
  onSelect(): void;
}) {
  const { t } = useTranslation();
  const noteId = useId();
  const unavailable = linkedTo !== null;

  return (
    <li>
      <label className="flex cursor-pointer items-center gap-2.5 rounded-md border border-transparent px-2.5 py-2 has-checked:border-border has-checked:bg-secondary has-disabled:cursor-default has-[input:disabled]:opacity-60 has-[input:focus-visible]:ring-3 has-[input:focus-visible]:ring-ring/50">
        <input
          type="radio"
          name={name}
          value={player.id}
          checked={checked}
          disabled={unavailable || disabled}
          aria-describedby={unavailable ? noteId : undefined}
          onChange={onSelect}
          className="size-4 shrink-0 accent-primary"
        />
        <ImageOrInitials
          path={null}
          name={player.fullName}
          fallbackIcon={User}
          className="size-7 rounded-full bg-secondary text-[0.6875rem] font-semibold text-secondary-foreground"
        />
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="flex flex-wrap items-center gap-1.5">
            <span className="font-medium">{player.fullName}</span>
            {player.primaryPosition !== null && <Badge dir="ltr">{player.primaryPosition}</Badge>}
            {player.jerseyNumber !== null && (
              <span dir="ltr" className="text-[0.8125rem] text-muted-foreground tabular-nums">
                #{player.jerseyNumber}
              </span>
            )}
            {!player.active && <ReleasedBadge />}
          </span>
          {unavailable && (
            <span id={noteId} className="text-[0.8125rem] text-muted-foreground">
              {t("stats.picker.linkedTo", { name: linkedTo })}
            </span>
          )}
        </span>
      </label>
    </li>
  );
}
