import { cn } from "@/lib/utils";

/** A small football card, decorative: the text beside it carries the meaning. */
export function CardMark({ color, className }: { color: "yellow" | "red"; className?: string }) {
  return (
    <span
      aria-hidden="true"
      className={cn(
        "inline-block h-3.5 w-2.5 shrink-0 rounded-[2px]",
        color === "yellow" ? "bg-card-yellow" : "bg-card-red",
        className,
      )}
    />
  );
}
