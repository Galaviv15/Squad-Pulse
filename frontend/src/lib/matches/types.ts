/**
 * The matches API's types, mirroring the backend records exactly (match.FixturesResponse,
 * match.LeagueTableResponse, scrapingintegration.RefreshStatusResponse). Instants are ISO-8601
 * strings in UTC ("2026-10-12T17:30:00Z"); `date` is an Israel-local yyyy-MM-dd and `kickoffTime`
 * an Israel-local HH:mm. No response carries a team, league, season or game id: a game's
 * `detailsId` (KAN-79) is our own opaque id.
 */

import type { StatsPlayerRef } from "./playerStats";

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

/** match.FixturesResponse.DetailsState: whether a played game's details (KAN-79) can be shown. */
export const DETAILS_STATES = ["AVAILABLE", "UNAVAILABLE", "PENDING"] as const;
export type DetailsState = (typeof DETAILS_STATES)[number];

/** match.FixturesResponse.Competition (KAN-84): which competition a game is in. */
export const COMPETITIONS = ["LEAGUE", "STATE_CUP"] as const;
export type Competition = (typeof COMPETITIONS)[number];

/** match.FixturesResponse.DecidedBy (KAN-84): the stage after the 90 minutes that decided a cup game. */
export const DECIDED_BY = ["EXTRA_TIME", "PENALTIES"] as const;
export type DecidedBy = (typeof DECIDED_BY)[number];

/** match.FixturesResponse.Score: a score from our side (after extra time, or a shootout's tally). */
export interface Score {
  ownScore: number;
  opponentScore: number;
}

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
  /**
   * The score after 90 minutes. Both are null while not played: "played" means both set (league or
   * cup), never read from `result`.
   */
  ownScore: number | null;
  opponentScore: number | null;
  /**
   * Who went through, from our side, by the last stage present: the shootout, else extra time, else
   * the 90 minutes. null while not played, and for a level shootout (bad data) even with scores.
   */
  result: Result | null;
  /** Our opaque id of the game's details (GET /matches/games/{id}); null unless AVAILABLE. */
  detailsId: string | null;
  /** null for a game not played yet. */
  detailsState: DetailsState | null;
  competition: Competition;
  /** A cup game's round as the cup list shows it (free text, e.g. "ו"); null for a league game. */
  round: string | null;
  /** A cup game's score after extra time (cumulative, incl. the 90 minutes); null without one. */
  extraTime: Score | null;
  /** A cup game's shootout tally only; null without one. */
  penalties: Score | null;
  /** PENALTIES with a shootout, else EXTRA_TIME with extra time, else null. */
  decidedBy: DecidedBy | null;
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

/** match.ScrapedGameDetails.Role: how one of our players took part in a game. */
export const APPEARANCE_ROLES = ["STARTER", "SUBSTITUTE", "UNUSED"] as const;
export type AppearanceRole = (typeof APPEARANCE_ROLES)[number];

/** match.ScrapedGameDetails.EventKind. A second yellow is one event (no separate red). */
export const GAME_EVENT_KINDS = [
  "GOAL",
  "YELLOW",
  "SECOND_YELLOW",
  "RED",
  "SUB_ON",
  "SUB_OFF",
] as const;
export type GameEventKind = (typeof GAME_EVENT_KINDS)[number];

/** match.GameDetailsResponse.Event. */
export interface GameEvent {
  kind: GameEventKind;
  /** As the site shows it (93, never 90+3); null if unknown. */
  minute: number | null;
}

/** match.GameDetailsResponse.Appearance: one of our players in the game. */
export interface GameAppearance {
  /** As the site shows it; null if the site masks it. */
  name: string | null;
  /** The club's player this one is linked to (KAN-72 links); null if none (or deleted). */
  linkedPlayer: StatsPlayerRef | null;
  /** Worn in this game, never the squad's jersey number; null if missing. */
  shirtNumber: number | null;
  role: AppearanceRole;
  goalkeeper: boolean;
  captain: boolean;
  /** By minute, an unknown minute last. */
  events: GameEvent[];
}

/**
 * match.GameDetailsResponse: GET /matches/games/{id} (KAN-79), one played game from our team's
 * point of view, our side only: the opponent is a name and a score. Our team's name isn't in it
 * (read it from the fixtures). Named apart from components/matches/GameRow's GameDetails.
 */
export interface PlayedGameDetails {
  /** Our opaque id, the same as the fixture's detailsId. */
  id: string;
  /** Israel-local yyyy-MM-dd. */
  date: string;
  /** Israel-local HH:mm; null if the site gave none. */
  kickoffTime: string | null;
  /** As the page's heading shows it, e.g. "ליגה א' דרום" (a cup's includes the sponsor). */
  competition: string;
  /** The page's own "מחזור N"; never shown for a cup game (it isn't the cup list's round). */
  round: number;
  ownSide: Side;
  opponentName: string;
  /** The page's score, after 90 minutes. */
  ownScore: number;
  opponentScore: number;
  /** As on the fixtures: who went through; null for a level shootout (bad data). */
  result: Result | null;
  /** As on the fixtures, for a cup game in the club's current cup data; else null. */
  extraTime: Score | null;
  penalties: Score | null;
  decidedBy: DecidedBy | null;
  /**
   * The cup (KAN-86) when the game is in the club's current cup data; null for a league game or a
   * cup game no longer in it (the two can't be told apart).
   */
  competitionType: Competition | null;
  /** That cup game's round as the cup list shows it; null whenever `competitionType` is. */
  cupRound: string | null;
  /** When the scraper fetched the page. */
  lastUpdatedAt: string;
  /** Starters, then substitutes who came on, then unused; within a role as sent. */
  players: GameAppearance[];
}
