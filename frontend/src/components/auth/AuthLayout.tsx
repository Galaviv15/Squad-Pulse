import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { BrandLogo } from "@/components/BrandLogo";
import { Card } from "@/components/ui/card";

/**
 * The page around every auth screen and the app-load states (loading, server error): the logo, the
 * content, and the footer line, centered on the page.
 */
export function AuthLayout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-6 bg-background px-4 py-12 text-foreground">
      <BrandLogo />
      {children}
      <p className="text-xs text-muted-foreground">{t("auth.footer")}</p>
    </main>
  );
}

interface AuthCardProps {
  title: string;
  subtitle?: string;
  /** Shown above the title (the server-error icon). */
  icon?: ReactNode;
  children?: ReactNode;
}

/** The auth screens' card: an optional icon, the page's <h1> and subtitle, then the content. */
export function AuthCard({ title, subtitle, icon, children }: AuthCardProps) {
  return (
    <Card className="w-full max-w-[420px] gap-5 px-7 py-8">
      {icon}
      <div className="flex flex-col gap-1.5">
        <h1 className="text-[1.375rem] leading-tight font-bold">{title}</h1>
        {subtitle && <p className="text-sm text-muted-foreground">{subtitle}</p>}
      </div>
      {children}
    </Card>
  );
}
