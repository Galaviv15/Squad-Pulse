import { useTranslation } from "react-i18next";
import { cn } from "@/lib/utils";

interface BrandLogoProps {
  /** "light" on a light background (the auth screens), "dark" on the dark sidebar (KAN-48). */
  tone?: "light" | "dark";
  className?: string;
}

/**
 * The SquadPulse logo (approved in KAN-47): a 40×40 mark with the pulse icon, beside the two-color
 * wordmark. One image to assistive technology, named "SquadPulse" once: the wordmark's two halves
 * are separate spans for their colors, which a screen reader could otherwise read as two words.
 * The wordmark is an LTR island; the mark comes first, at the start side.
 */
export function BrandLogo({ tone = "light", className }: BrandLogoProps) {
  const { t } = useTranslation();
  const dark = tone === "dark";

  return (
    <div
      role="img"
      aria-label={t("app.name")}
      className={cn("inline-flex items-center gap-2.5", className)}
    >
      <span
        aria-hidden="true"
        className={cn(
          "flex size-10 shrink-0 items-center justify-center rounded-lg",
          dark ? "bg-sidebar-accent text-brand-pulse" : "bg-sidebar text-brand-pulse-bright",
        )}
      >
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          className="size-[22px]"
        >
          <path d="M3 12h4l3-7 4 14 3-7h4" />
        </svg>
      </span>
      <span
        aria-hidden="true"
        dir="ltr"
        className="text-[1.375rem] leading-none font-bold tracking-[-0.01em]"
      >
        <span className={dark ? "text-sidebar-accent" : "text-sidebar"}>
          {t("app.wordmark.squad")}
        </span>
        <span className={dark ? "text-brand-pulse-bright" : "text-brand-pulse"}>
          {t("app.wordmark.pulse")}
        </span>
      </span>
    </div>
  );
}
