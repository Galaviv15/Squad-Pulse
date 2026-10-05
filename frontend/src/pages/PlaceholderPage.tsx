import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/** Stands in for /app until the real screens exist; shows the base components under RTL. */
export function PlaceholderPage() {
  const { t } = useTranslation();

  return (
    <main className="flex min-h-screen items-center justify-center bg-background p-8 text-foreground">
      <Card className="w-full max-w-md">
        <CardHeader>
          <CardTitle>
            <h1 className="text-2xl font-bold text-primary">{t("app.name")}</h1>
          </CardTitle>
          <CardDescription>{t("app.welcome")}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col items-start gap-4">
          <p className="text-muted-foreground">{t("app.phaseNote")}</p>
          {/* Position codes stay in English, as an LTR island (docs/design/ui-conventions.md). */}
          <Badge variant="secondary" dir="ltr">
            CB
          </Badge>
          <Button>{t("placeholder.comingSoon")}</Button>
        </CardContent>
      </Card>
    </main>
  );
}
