import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { useRouteError } from "react-router";
import { Button } from "@/components/ui/button";

/**
 * The error element of every page inside the app shell (the shell and its title stay): a lazy
 * page whose code failed to load (a chunk gone after a deploy, a network drop), or a page that
 * threw while rendering. React Router caches a lazy route's rejected import for the life of the
 * page, so only a reload can load it again, which is what the button does. The error is logged,
 * never swallowed: a page that throws is a bug to see in the console.
 */
export function RouteErrorPage() {
  const { t } = useTranslation();
  const error = useRouteError();

  useEffect(() => {
    console.error("A page failed to load or render:", error);
  }, [error]);

  return (
    <div role="alert" className="flex flex-col items-start gap-4">
      <p className="text-sm text-muted-foreground">{t("routeError.message")}</p>
      <Button variant="outline" size="sm" onClick={() => window.location.reload()}>
        {t("routeError.reload")}
      </Button>
    </div>
  );
}
