import { LogOut, User } from "lucide-react";
import { useTranslation } from "react-i18next";
import { ImageOrInitials } from "@/components/ImageOrInitials";
import { Button } from "@/components/ui/button";
import { useCurrentUser } from "@/lib/auth/currentUser";
import { useLogout } from "@/lib/auth/logout";
import { TITLE_KEYS } from "@/lib/auth/titles";

/**
 * The content column's top bar: the page title (the page's only <h1>) on the start side, the
 * logged-in user and the logout button on the end side. Wraps on narrow widths.
 */
export function TopBar({ title }: { title: string | null }) {
  return (
    <header className="flex flex-wrap items-center justify-between gap-4 border-b border-border bg-card px-4 py-4 md:px-8">
      {title !== null && <h1 className="text-[1.375rem] leading-tight font-bold">{title}</h1>}
      <UserBlock />
    </header>
  );
}

/** Avatar (photo only when the user has one), name and title, and logout. */
function UserBlock() {
  const { t } = useTranslation();
  const user = useCurrentUser();
  const { logout, pending } = useLogout();

  return (
    <div className="ms-auto flex min-w-0 items-center gap-3">
      <ImageOrInitials
        path={user.hasPhoto ? "/users/me/photo" : null}
        name={user.fullName}
        fallbackIcon={User}
        className="size-9 rounded-full bg-secondary text-[13px] font-semibold text-secondary-foreground"
        imageClassName="object-cover"
      />
      <div className="flex max-w-56 min-w-0 flex-col leading-[1.3]">
        <span title={user.fullName} className="truncate text-sm font-semibold">
          {user.fullName}
        </span>
        <span className="truncate text-xs text-muted-foreground">{t(TITLE_KEYS[user.title])}</span>
      </div>
      <Button
        variant="outline"
        size="sm"
        className="ms-2"
        disabled={pending}
        onClick={() => void logout()}
      >
        {/* lucide's arrow points right; mirrored under RTL it points to the end side (left). */}
        <LogOut aria-hidden="true" className="size-4 rtl:-scale-x-100" />
        {t("shell.logout")}
      </Button>
    </div>
  );
}
