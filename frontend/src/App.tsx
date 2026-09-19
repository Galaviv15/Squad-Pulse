import { useTranslation } from "react-i18next";

function App() {
  const { t } = useTranslation();

  return (
    <div className="min-h-screen bg-neutral-50 flex items-center justify-center">
      <div className="text-center">
        <h1 className="text-3xl font-bold text-emerald-800">{t("app.name")}</h1>
        <p className="mt-2 text-neutral-600">{t("app.welcome")}</p>
        <p className="mt-6 text-sm text-neutral-400">{t("app.phaseNote")}</p>
      </div>
    </div>
  );
}

export default App;
