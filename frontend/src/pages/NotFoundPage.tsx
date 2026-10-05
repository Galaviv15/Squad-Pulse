import { useTranslation } from "react-i18next";
import { Link } from "react-router";
import { buttonVariants } from "@/components/ui/button";

export function NotFoundPage() {
  const { t } = useTranslation();

  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-background p-8 text-center text-foreground">
      <h1 className="text-2xl font-bold">{t("notFound.title")}</h1>
      <p className="text-muted-foreground">{t("notFound.description")}</p>
      {/* A real link styled as a button: Base UI's Button would give the <a> role="button". */}
      <Link to="/app" className={buttonVariants()}>
        {t("notFound.backToApp")}
      </Link>
    </main>
  );
}
