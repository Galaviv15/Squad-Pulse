import { describe, expect, it } from "vitest";
import {
  cupGame,
  cupGameOnPenalties,
  cupGameWithoutResult,
  gameBody,
  playedGame,
} from "@/test/msw/matches";
import { gameStatus, nextGame } from "./games";

const TODAY = "2026-10-12";

describe("gameStatus", () => {
  it("is played once a game has a result, whatever its date", () => {
    expect(gameStatus(playedGame({ date: "2026-10-01" }), TODAY)).toBe("played");
    expect(gameStatus(playedGame({ date: TODAY }), TODAY)).toBe("played");
  });

  it("is upcoming without a result from today on", () => {
    expect(gameStatus(gameBody({ date: TODAY }), TODAY)).toBe("upcoming");
    expect(gameStatus(gameBody({ date: "2026-10-13" }), TODAY)).toBe("upcoming");
  });

  it("is noResult without a result before today", () => {
    expect(gameStatus(gameBody({ date: "2026-10-11" }), TODAY)).toBe("noResult");
  });

  it("is played from the two scores, even with a null result (a level shootout, KAN-86)", () => {
    expect(gameStatus(cupGameWithoutResult({ date: "2026-10-01" }), TODAY)).toBe("played");
    expect(gameStatus(cupGameWithoutResult({ date: TODAY }), TODAY)).toBe("played");
    expect(gameStatus(cupGameWithoutResult({ date: "2026-10-20" }), TODAY)).toBe("played");
  });

  it("is not played with only one score", () => {
    expect(gameStatus(gameBody({ date: TODAY, ownScore: 1 }), TODAY)).toBe("upcoming");
    expect(gameStatus(gameBody({ date: "2026-10-11", opponentScore: 1 }), TODAY)).toBe("noResult");
  });
});

describe("nextGame", () => {
  it("skips played games and past games without a result", () => {
    const next = gameBody({ date: "2026-10-17", opponentName: "Next" });
    const games = [
      playedGame({ date: "2026-10-01" }),
      gameBody({ date: "2026-10-05", opponentName: "Postponed" }),
      next,
      gameBody({ date: "2026-10-24" }),
    ];
    expect(nextGame(games, TODAY)).toBe(next);
  });

  it("is today's unplayed game", () => {
    const today = gameBody({ date: TODAY, opponentName: "Today" });
    expect(nextGame([gameBody({ date: "2026-10-11" }), today, gameBody()], TODAY)).toBe(today);
  });

  it("skips today's game once it has a result", () => {
    const later = gameBody({ date: "2026-10-17" });
    expect(nextGame([playedGame({ date: TODAY }), later], TODAY)).toBe(later);
  });

  it("skips a played cup game without a result (KAN-86)", () => {
    const next = gameBody({ date: "2026-10-17", opponentName: "Next" });
    expect(nextGame([cupGameWithoutResult({ date: TODAY }), next], TODAY)).toBe(next);
  });

  it("spans league and cup: an upcoming cup game is next when it's the earliest", () => {
    const cup = cupGame({ date: "2026-10-14" });
    const games = [
      cupGameOnPenalties({ date: "2026-10-05" }),
      cup,
      gameBody({ date: "2026-10-17" }),
    ];
    expect(nextGame(games, TODAY)).toBe(cup);
  });

  it("is null when no game is upcoming", () => {
    expect(
      nextGame([playedGame({ date: "2026-10-01" }), gameBody({ date: "2026-10-11" })], TODAY),
    ).toBeNull();
    expect(nextGame([], TODAY)).toBeNull();
  });
});
