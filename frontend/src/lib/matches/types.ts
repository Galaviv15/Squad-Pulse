/**
 * The matches API's types, mirroring the backend records exactly (match.FixturesResponse,
 * match.LeagueTableResponse, scrapingintegration.RefreshStatusResponse). Instants are ISO-8601
 * strings in UTC ("2026-10-12T17:30:00Z"); `date` is an Israel-local yyyy-MM-dd and `kickoffTime`
 * an Israel-local HH:mm. Neither response carries a team, league, season or game id.
 */

/** match.MatchDataState: whether a read has data to show. */
export const MATCH_DATA_STATES = ["NOT_MAPPED", "NO_DATA", "OK"] as const;
export type MatchDataState = (typeof MATCH_DATA_STATES)[number];

/** match.MappingProblem. */
export const MAPPING_PROBLEMS = ["TEAM_NOT_IN_STANDINGS"] as const;
export type MappingProblem = (typeof MAPPING_PROBLEMS)[number];

/** match.FixturesResponse.Side: our team's side. */
export const SIDES = ["HOME", "AWAY"] as const;
export type Side = (typeof SIDES)[number];

/** match.FixturesResponse.Result, from our team's point of view. */
export const RESULTS = ["WIN", "DRAW", "LOSS"] as const;
export type Result = (typeof RESULTS)[number];

/** match.FixturesResponse.Game. */
export interface Game {
  /** Israel-local yyyy-MM-dd. */
  date: string;
  /** Israel-local HH:mm; null while not set yet. */
  kickoffTime: string | null;
  ownSide: Side;
  opponentName: string;
  /** null when the site shows none. */
  venue: string | null;
  /** Both scores and `result` are null while not played. */
  ownScore: number | null;
  opponentScore: number | null;
  result: Result | null;
}

/** match.FixturesResponse: GET /matches/fixtures. */
export interface Fixtures {
  state: MatchDataState;
  /** When the shown data was scraped; null without data. */
  lastUpdatedAt: string | null;
  /** Our team's name as the site shows it; null without data. */
  teamName: string | null;
  mappingProblem: MappingProblem | null;
  /** By date, then kickoff time, an unset time last within its day: shown in this order. */
  games: Game[];
}

/** match.LeagueTableResponse.Row. */
export interface LeagueTableRow {
  position: number;
  teamName: string;
  played: number;
  won: number;
  drawn: number;
  lost: number;
  goalsFor: number;
  goalsAgainst: number;
  goalDifference: number;
  points: number;
  ownTeam: boolean;
}

/** match.LeagueTableResponse: GET /matches/league-table. */
export interface LeagueTable {
  state: MatchDataState;
  /** When the shown table was scraped (possibly by another club of the league); null without one. */
  lastUpdatedAt: string | null;
  /** null without data. */
  round: number | null;
  mappingProblem: MappingProblem | null;
  /** By position. */
  rows: LeagueTableRow[];
}

/** RefreshStatusResponse.Outcome: what a POST did (null on a GET). Only STARTED is a 202. */
export const REFRESH_OUTCOMES = [
  "STARTED",
  "ALREADY_RUNNING",
  "COOLDOWN",
  "NOT_MAPPED",
  "SCRAPER_UNAVAILABLE",
] as const;
export type RefreshOutcome = (typeof REFRESH_OUTCOMES)[number];

/** RefreshStatusResponse.State: the club's latest scrape run. */
export const REFRESH_STATES = ["NOT_MAPPED", "IDLE", "RUNNING", "SUCCEEDED", "FAILED"] as const;
export type RefreshState = (typeof REFRESH_STATES)[number];

/** RefreshStatusResponse.FailureReason: set only with FAILED. */
export const FAILURE_REASONS = [
  "SOURCE_BLOCKED",
  "SOURCE_TIMEOUT",
  "SOURCE_ERROR",
  "PARSE_ERROR",
  "REJECTED",
  "NO_RESULT",
] as const;
export type FailureReason = (typeof FAILURE_REASONS)[number];

/** scrapingintegration.RefreshStatusResponse: POST and GET /matches/refresh. */
export interface RefreshStatus {
  outcome: RefreshOutcome | null;
  state: RefreshState;
  failureReason: FailureReason | null;
  startedAt: string | null;
  finishedAt: string | null;
  /** When a POST may next start a scrape; null if it may now. */
  nextRefreshAt: string | null;
}
