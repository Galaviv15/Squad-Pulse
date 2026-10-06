import { useTranslation } from "react-i18next";

/** /app: a stub until the dashboard (KAN-51). Its title comes from the route (the shell's <h1>). */
export function DashboardPage() {
  const { t } = useTranslation();

  return <p className="text-sm text-muted-foreground">{t("dashboard.stub")}</p>;
}
