import { RotateCw, WifiOff } from "lucide-react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { AuthCard, AuthLayout } from "./AuthLayout";

/** The whole page while the session or the current user loads. Never a form or app content. */
export function LoadingScreen() {
  const { t } = useTranslation();

  return (
    <AuthLayout>
      <div role="status" className="flex min-h-[200px] flex-col items-center justify-center gap-3">
        {/* A ring with a --primary arc; without motion it stays a still ring. */}
        <span
          aria-hidden="true"
          className="size-8 rounded-full border-[3px] border-border border-t-primary motion-safe:animate-spin"
        />
        <p className="text-sm text-muted-foreground">{t("auth.loading")}</p>
      </div>
    </AuthLayout>
  );
}

/**
 * The whole page when the server couldn't be reached at app load (the refresh or /me got a 5xx or
 * no response), so it's unknown whether there's a session: neither the app nor the login form.
 */
export function ServerErrorScreen({ onRetry }: { onRetry: () => void }) {
  const { t } = useTranslation();

  return (
    <AuthLayout>
      <AuthCard
        title={t("auth.serverError.title")}
        subtitle={t("auth.serverError.body")}
        icon={
          <span className="flex size-11 items-center justify-center rounded-full bg-danger-muted text-danger">
            <WifiOff aria-hidden="true" className="size-[22px]" />
          </span>
        }
      >
        <Button className="w-full font-semibold" onClick={onRetry}>
          <RotateCw aria-hidden="true" className="size-4" />
          {t("auth.serverError.retry")}
        </Button>
      </AuthCard>
    </AuthLayout>
  );
}
