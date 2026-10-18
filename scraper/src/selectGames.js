// Which game pages one scrape fetches (KAN-75 findings, "Decided for KAN-79"; KAN-80; cup rows
// since KAN-85).

export const MAX_GAMES_PER_SCRAPE = 2;
export const SETTLE_MS = 24 * 60 * 60 * 1000;
// A kickoff the site doesn't show (the parser's `null`) counts as the day's last minute: later, so
// the 24 h rule never lets such a game through too early.
const UNKNOWN_KICKOFF = "23:59";
const ISRAEL = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Jerusalem",
  hourCycle: "h23",
  year: "numeric",
  month: "numeric",
  day: "numeric",
  hour: "numeric",
  minute: "numeric",
  second: "numeric",
});

/**
 * The rows game details pick from: the team-games rows (`kind` `LEAGUE`), then the cup rows (`kind`
 * `CUP`, from a cup list that parsed in this scrape). A `gameId` in both lists is kept once, as its
 * league row, and listed in `duplicates` (unseen: the team-games page has no cup game).
 */
export function combineRows(leagueRows, cupRows) {
  const rows = leagueRows.map((row) => ({ ...row, kind: "LEAGUE" }));
  const leagueIds = new Set(leagueRows.map((row) => row.gameId).filter((id) => id !== null));
  const duplicates = [];
  for (const row of cupRows) {
    if (row.gameId !== null && leagueIds.has(row.gameId)) duplicates.push(row.gameId);
    else rows.push({ ...row, kind: "CUP" });
  }
  return { rows, duplicates };
}

/**
 * From the rows ({@link combineRows}, or the team-games rows alone): games with a `gameId` and both scores, not in `knownGameIds`, whose
 * kickoff (Israel time) is at least 24 h before `now`; sorted by date, then kickoff time, then
 * `gameId` (ids aren't chronological); the first {@link MAX_GAMES_PER_SCRAPE}, oldest first.
 */
export function selectGames(rows, knownGameIds, now) {
  const known = new Set(knownGameIds);
  return rows
    .filter(
      (row) =>
        row.gameId !== null &&
        row.homeScore !== null &&
        row.awayScore !== null &&
        !known.has(row.gameId),
    )
    .map((row) => ({ row, time: row.kickoffTime ?? UNKNOWN_KICKOFF }))
    .filter(({ row, time }) => now - israelInstant(row.date, time) >= SETTLE_MS)
    .sort(
      (a, b) =>
        compare(a.row.date, b.row.date) || compare(a.time, b.time) || a.row.gameId - b.row.gameId,
    )
    .slice(0, MAX_GAMES_PER_SCRAPE)
    .map(({ row }) => row);
}

const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

/** `yyyy-MM-dd` + `HH:mm`, wall time in Asia/Jerusalem → epoch ms (DST from the time-zone data). */
export function israelInstant(date, time) {
  const [year, month, day] = date.split("-").map(Number);
  const [hour, minute] = time.split(":").map(Number);
  const wall = Date.UTC(year, month - 1, day, hour, minute);
  // The offset at the wall time read as UTC is at most one DST step off; a second pass fixes it.
  const first = wall - israelOffset(wall);
  return wall - israelOffset(first);
}

/** Israel's UTC offset at an instant, in ms. */
function israelOffset(instant) {
  const parts = Object.fromEntries(
    ISRAEL.formatToParts(new Date(instant)).map(({ type, value }) => [type, Number(value)]),
  );
  const asUtc = Date.UTC(
    parts.year,
    parts.month - 1,
    parts.day,
    parts.hour,
    parts.minute,
    parts.second,
  );
  return asUtc - Math.floor(instant / 1000) * 1000;
}
