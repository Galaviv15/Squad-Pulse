import { describe, expect, it } from "vitest";
import type { Line } from "@/lib/squad/types";
import {
  DONUT_CIRCUMFERENCE,
  DONUT_GAP,
  DONUT_MIN_LENGTH,
  donutSegments,
  type DonutSegment,
} from "./donut";

const lines = (
  GOALKEEPERS: number,
  DEFENSE: number,
  MIDFIELD: number,
  ATTACK: number,
): Record<Line, number> => ({ GOALKEEPERS, DEFENSE, MIDFIELD, ATTACK });

const total = (segments: DonutSegment[]) =>
  segments.reduce((sum, segment) => sum + segment.length, 0);

describe("donutSegments", () => {
  it("gives one segment per line in line order, sharing the ring minus the gaps by count", () => {
    const segments = donutSegments(lines(3, 8, 7, 5));

    expect(segments.map(({ line, count }) => [line, count])).toEqual([
      ["GOALKEEPERS", 3],
      ["DEFENSE", 8],
      ["MIDFIELD", 7],
      ["ATTACK", 5],
    ]);
    const available = DONUT_CIRCUMFERENCE - 4 * DONUT_GAP;
    expect(total(segments)).toBeCloseTo(available, 9);
    segments.forEach((segment) => {
      expect(segment.length).toBeCloseTo((segment.count / 23) * available, 9);
    });
  });

  it("starts at the top and leaves exactly one gap after each segment", () => {
    const segments = donutSegments(lines(3, 8, 7, 5));

    expect(segments[0].start).toBe(0);
    for (let i = 1; i < segments.length; i++) {
      expect(segments[i].start).toBeCloseTo(
        segments[i - 1].start + segments[i - 1].length + DONUT_GAP,
        9,
      );
    }
    const last = segments[segments.length - 1];
    expect(last.start + last.length + DONUT_GAP).toBeCloseTo(DONUT_CIRCUMFERENCE, 9);
  });

  it("leaves out an empty line, with no gap for it", () => {
    const segments = donutSegments(lines(2, 0, 6, 0));

    expect(segments.map((segment) => segment.line)).toEqual(["GOALKEEPERS", "MIDFIELD"]);
    expect(total(segments)).toBeCloseTo(DONUT_CIRCUMFERENCE - 2 * DONUT_GAP, 9);
  });

  it("gives no segment at all when every line is empty", () => {
    expect(donutSegments(lines(0, 0, 0, 0))).toEqual([]);
  });

  it("draws a single line as the full ring, without a gap", () => {
    expect(donutSegments(lines(0, 0, 0, 12))).toEqual([
      { line: "ATTACK", count: 12, start: 0, length: DONUT_CIRCUMFERENCE },
    ]);
  });

  it("keeps a one-player line visible in a big squad, at the minimum length", () => {
    const segments = donutSegments(lines(1, 60, 60, 60));

    // Its share alone would be (1 / 181) × (ring − gaps) ≈ 2.0, under the minimum.
    expect(segments[0]).toMatchObject({ line: "GOALKEEPERS", count: 1 });
    expect(segments[0].length).toBe(DONUT_MIN_LENGTH);
    // The others share the rest equally, and everything still fits the ring.
    const rest = (DONUT_CIRCUMFERENCE - 4 * DONUT_GAP - DONUT_MIN_LENGTH) / 3;
    segments.slice(1).forEach((segment) => expect(segment.length).toBeCloseTo(rest, 9));
    expect(total(segments)).toBeCloseTo(DONUT_CIRCUMFERENCE - 4 * DONUT_GAP, 9);
  });

  it("keeps several small lines visible, each at least the minimum", () => {
    const segments = donutSegments(lines(1, 1, 500, 1));

    expect(segments.map((segment) => segment.length)).toEqual([
      DONUT_MIN_LENGTH,
      DONUT_MIN_LENGTH,
      expect.closeTo(DONUT_CIRCUMFERENCE - 4 * DONUT_GAP - 3 * DONUT_MIN_LENGTH, 9),
      DONUT_MIN_LENGTH,
    ]);
  });

  it("never yields a non-finite value", () => {
    for (const input of [lines(0, 0, 0, 0), lines(1, 0, 0, 0), lines(1, 60, 60, 60)]) {
      for (const segment of donutSegments(input)) {
        expect(Number.isFinite(segment.start)).toBe(true);
        expect(Number.isFinite(segment.length)).toBe(true);
      }
    }
  });
});
