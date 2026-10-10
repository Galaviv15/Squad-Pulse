import {
  CalendarDays,
  ClipboardList,
  Clock,
  LayoutDashboard,
  Shield,
  Trophy,
  Users,
  type LucideIcon,
} from "lucide-react";
import { useId } from "react";
import { useTranslation } from "react-i18next";
import { NavLink } from "react-router";
import { BrandLogo } from "@/components/BrandLogo";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { cn } from "@/lib/utils";

interface NavItem {
  to: string;
  labelKey: string;
  icon: LucideIcon;
  /** Active only on exactly `to`, not below it. */
  end?: boolean;
}

/**
 * The live pages. A new page gets its route (with a titleKey handle) and an entry here. Each links
 * to its page fresh: "סגל" is the bare squad, never the last filters and view seen (those are for
 * the "חזרה לסגל" links), so clicking it on a filtered squad resets the filters.
 */
const NAV_ITEMS: NavItem[] = [
  { to: "/app", labelKey: "nav.dashboard", icon: LayoutDashboard, end: true },
  { to: "/app/squad", labelKey: "nav.squad", icon: Users },
];

/** Shown disabled until they exist: plain text, never links or buttons. */
const COMING_SOON: { labelKey: string; icon: LucideIcon }[] = [
  { labelKey: "shell.comingSoon.schedule", icon: Clock },
  { labelKey: "shell.comingSoon.training", icon: CalendarDays },
  { labelKey: "shell.comingSoon.matches", icon: Trophy },
  { labelKey: "shell.comingSoon.tactics", icon: ClipboardList },
];

const ITEM_ROW = "flex items-center gap-2.5 rounded-md px-3 py-2.5 text-sm";
const ITEM_ICON = "size-[18px] shrink-0";

/**
 * The dark green sidebar on the start side (right in RTL): the club, the navigation, the "coming
 * soon" group and the logo. Sticky beside the content from md up; stacked above it on a phone.
 */
export function Sidebar() {
  const { t } = useTranslation();
  const comingSoonLabelId = useId();

  return (
    <div className="flex w-full shrink-0 flex-col gap-6 bg-sidebar px-3.5 py-5 text-sidebar-foreground md:sticky md:top-0 md:h-screen md:w-60 md:overflow-y-auto">
      <ClubBlock />
      <div className="flex flex-col">
        <nav aria-label={t("shell.navLabel")}>
          {/* role="list": Safari drops list semantics from a list without list-style. */}
          <ul role="list" className="flex flex-col gap-1">
            {NAV_ITEMS.map(({ to, labelKey, icon: Icon, end }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    cn(
                      ITEM_ROW,
                      "outline-none focus-visible:ring-2 focus-visible:ring-sidebar-accent focus-visible:ring-offset-2 focus-visible:ring-offset-sidebar",
                      isActive
                        ? "bg-sidebar-accent font-semibold text-sidebar-accent-foreground"
                        : "font-medium text-sidebar-foreground hover:bg-sidebar-foreground/10",
                    )
                  }
                >
                  <Icon aria-hidden="true" className={ITEM_ICON} />
                  {t(labelKey)}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
        <div className="mt-4">
          <p id={comingSoonLabelId} className="px-3 text-xs text-sidebar-muted-foreground">
            {t("shell.comingSoon.label")}
          </p>
          <ul role="list" aria-labelledby={comingSoonLabelId} className="mt-1 flex flex-col gap-1">
            {COMING_SOON.map(({ labelKey, icon: Icon }) => (
              <li
                key={labelKey}
                className={cn(ITEM_ROW, "cursor-default text-sidebar-muted-foreground opacity-75")}
              >
                <Icon aria-hidden="true" className={ITEM_ICON} />
                {t(labelKey)}
              </li>
            ))}
          </ul>
        </div>
      </div>
      <BrandLogo tone="dark" className="mt-auto" />
    </div>
  );
}

/** The club's logo (or initials) and name. The logo is fetched only when the club has one. */
function ClubBlock() {
  const { club } = useCurrentUser();

  return (
    <div className="flex items-center gap-3 px-2 py-1">
      <ImageOrInitials
        path={club.hasLogo ? "/clubs/me/logo" : null}
        name={club.name}
        fallbackIcon={Shield}
        className="size-10 rounded-lg bg-sidebar-accent text-[15px] font-bold text-sidebar-accent-foreground"
        imageClassName="object-contain"
      />
      <p
        title={club.name}
        className="line-clamp-2 min-w-0 text-[15px] font-semibold break-words text-sidebar-foreground"
      >
        {club.name}
      </p>
    </div>
  );
}
