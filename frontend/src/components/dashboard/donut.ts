import { LINES, type Line } from "@/lib/squad/types";

/*
 * The squad-by-line donut's geometry, kept apart from the component so it can be tested without a
 * DOM. Lengths are along the ring's center line (r = 60), in SVG user units, the units of the
 * circles' stroke-dasharray.
 */

/** The ring's center-line radius; the stroke (22) is centered on it. */
export const DONUT_RADIUS = 60;

export const DONUT_CIRCUMFERENCE = 2 * Math.PI * DONUT_RADIUS;

/** The empty stretch after each segment, when there are two or more. */
export const DONUT_GAP = 3;

/** The shortest a segment gets, so a one-player line in a big squad never disappears. */
export const DONUT_MIN_LENGTH = 4;

/** One line's arc: from `start` (0 = the top, clockwise) for `length`. */
export interface DonutSegment {
  line: Line;
  count: number;
  start: number;
  length: number;
}

/**
 * The donut's segments: one per line with a count above 0, in LINES order (the legend's), each
 * followed by a gap; together they cover the ring exactly. None at all for an empty squad (the
 * component draws an empty ring), and a single line is the full ring without a gap.
 */
export function donutSegments(lines: Record<Line, number>): DonutSegment[] {
  const present = LINES.filter((line) => Number.isFinite(lines[line]) && lines[line] > 0);
  if (present.length === 0) {
    return [];
  }
  if (present.length === 1) {
    const line = present[0];
    return [{ line, count: lines[line], start: 0, length: DONUT_CIRCUMFERENCE }];
  }

  const lengths = proportionalLengths(
    present.map((line) => lines[line]),
    DONUT_CIRCUMFERENCE - present.length * DONUT_GAP,
  );
  let start = 0;
  return present.map((line, index) => {
    const segment = { line, count: lines[line], start, length: lengths[index] };
    start += lengths[index] + DONUT_GAP;
    return segment;
  });
}

/**
 * Splits `total` in proportion to `counts` (all above 0), giving none less than DONUT_MIN_LENGTH:
 * a share that falls short is set to the minimum and the rest is split again among the others,
 * until none falls short (at most one round per count). The lengths always sum to `total`.
 */
function proportionalLengths(counts: number[], total: number): number[] {
  const atMinimum = new Set<number>();
  for (;;) {
    const free = total - atMinimum.size * DONUT_MIN_LENGTH;
    const freeCount = counts.reduce((sum, count, i) => (atMinimum.has(i) ? sum : sum + count), 0);
    const lengths = counts.map((count, i) =>
      atMinimum.has(i) ? DONUT_MIN_LENGTH : (count / freeCount) * free,
    );
    const short = lengths.flatMap((length, i) =>
      !atMinimum.has(i) && length < DONUT_MIN_LENGTH ? [i] : [],
    );
    if (short.length === 0) {
      return lengths;
    }
    short.forEach((i) => atMinimum.add(i));
  }
}

/**
 * Each line's chart color (KAN-44: GK chart-1, DEF chart-2, MID chart-3, ATT chart-4): the ring's
 * stroke and the legend's swatch. Full class names, so Tailwind generates them.
 */
export const LINE_COLORS: Record<Line, { stroke: string; swatch: string }> = {
  GOALKEEPERS: { stroke: "stroke-chart-1", swatch: "bg-chart-1" },
  DEFENSE: { stroke: "stroke-chart-2", swatch: "bg-chart-2" },
  MIDFIELD: { stroke: "stroke-chart-3", swatch: "bg-chart-3" },
  ATTACK: { stroke: "stroke-chart-4", swatch: "bg-chart-4" },
};
