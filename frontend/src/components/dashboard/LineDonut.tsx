import { useTranslation } from "react-i18next";
import { LINE_KEYS } from "@/lib/squad/labels";
import type { Line } from "@/lib/squad/types";
import {
  DONUT_CIRCUMFERENCE,
  DONUT_RADIUS,
  LINE_COLORS,
  donutSegments,
  type DonutSegment,
} from "./donut";

const SIZE = 168;
const CENTER = SIZE / 2;
const STROKE_WIDTH = 22;

/**
 * The squad by line as a plain-SVG donut (no chart library): one circle per segment, its arc drawn
 * with stroke-dasharray / stroke-dashoffset and the whole ring rotated -90° so it starts at the
 * top and runs clockwise, in the legend's order. With no players it's one empty muted ring. The
 * center shows `total`: the summary's playerCount, which can be more than the lines add up to.
 *
 * One role="img" with a label: the numbers themselves are read from the legend, which is text.
 */
export function LineDonut({ lines, total }: { lines: Record<Line, number>; total: number }) {
  const { t } = useTranslation();
  const segments = donutSegments(lines);

  return (
    <div className="relative size-[168px] shrink-0">
      <svg
        viewBox={`0 0 ${SIZE} ${SIZE}`}
        className="size-full"
        role="img"
        aria-label={t("dashboard.lines.chartLabel")}
      >
        <g transform={`rotate(-90 ${CENTER} ${CENTER})`} fill="none" strokeWidth={STROKE_WIDTH}>
          {segments.length === 0 ? (
            <circle cx={CENTER} cy={CENTER} r={DONUT_RADIUS} className="stroke-muted" />
          ) : (
            segments.map((segment) => (
              <circle
                key={segment.line}
                cx={CENTER}
                cy={CENTER}
                r={DONUT_RADIUS}
                className={LINE_COLORS[segment.line].stroke}
                {...dashProps(segment)}
              >
                <title>
                  {t("dashboard.lines.segmentTitle", {
                    line: t(LINE_KEYS[segment.line]),
                    count: segment.count,
                  })}
                </title>
              </circle>
            ))
          )}
        </g>
      </svg>
      <div className="absolute inset-0 flex flex-col items-center justify-center">
        <span className="text-[1.75rem] leading-none font-bold tabular-nums">{total}</span>
        <span className="mt-1 text-xs text-muted-foreground">
          {t("dashboard.lines.centerUnit")}
        </span>
      </div>
    </div>
  );
}

/**
 * A segment's dash: `length` drawn, the rest of the ring empty, shifted to start at `start`. The
 * full ring (a single line) has no dash at all, so no rounding can leave a hairline at the top.
 */
function dashProps({ start, length }: DonutSegment) {
  if (length >= DONUT_CIRCUMFERENCE) {
    return {};
  }
  return {
    strokeDasharray: `${round(length)} ${round(DONUT_CIRCUMFERENCE - length)}`,
    strokeDashoffset: round(-start),
  };
}

function round(value: number) {
  return Math.round(value * 1000) / 1000;
}
