import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { buttonVariants } from "@/components/ui/button";

/** The full-page not-found, for a path outside /app (no session, no shell). */
export function NotFoundPage() {
  const { t } = useTranslation();

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-background p-8 text-center text-foreground">
      <h1 className="text-2xl font-bold">{t("notFound.title")}</h1>
      <NotFoundBody />
    </main>
  );
}

/**
 * An unknown /app path, inside the shell: the shell's <h1> carries the title (the route's
 * handle), so this is only the description and the link back.
 */
export function NotFoundInShellPage() {
  return (
    <div className="flex flex-col items-start gap-4">
      <NotFoundBody />
    </div>
  );
}

function NotFoundBody() {
  const { t } = useTranslation();

  return (
    <>
      <p className="text-muted-foreground">{t("notFound.description")}</p>
      {/* A real link styled as a button: Base UI's Button would give the <a> role="button". */}
      <Link to="/app" className={buttonVariants()}>
        {t("notFound.backToApp")}
      </Link>
    </>
  );
}
