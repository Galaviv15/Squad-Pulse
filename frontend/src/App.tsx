import { useTranslation } from "react-i18next";

function App() {
  const { t } = useTranslation();

  return (
    <div className="flex min-h-screen items-center justify-center bg-background">
      <div className="text-center">
        <h1 className="text-3xl font-bold text-primary">{t("app.name")}</h1>
        <p className="mt-2 text-foreground">{t("app.welcome")}</p>
        <p className="mt-6 text-sm text-muted-foreground">{t("app.phaseNote")}</p>
      </div>
    </div>
  );
}

export default App;
