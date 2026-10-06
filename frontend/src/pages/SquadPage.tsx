import { useTranslation } from "react-i18next";

/** /app/squad: a stub until the squad table (KAN-49). Its title comes from the route. */
export function SquadPage() {
  const { t } = useTranslation();

  return <p className="text-sm text-muted-foreground">{t("squad.stub")}</p>;
}
