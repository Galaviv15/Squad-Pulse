import { useTranslation } from "react-i18next";
import { Badge } from "@/components/ui/badge";
import { MEDICAL_STATUS_KEYS } from "@/lib/squad/labels";
import type { MedicalStatus, Position } from "@/lib/squad/types";

/** A missing value: a muted dash. */
export function None() {
  return <span className="text-muted-foreground">—</span>;
}

/**
 * A player's position chips, in LTR islands (the codes stay in English): the primary filled, the
 * secondary outlined. A dash when there's neither.
 */
export function PositionChips({
  primary,
  secondary,
}: {
  primary: Position | null;
  secondary: Position | null;
}) {
  if (primary === null && secondary === null) {
    return <None />;
  }
  return (
    <div className="flex items-center gap-1.5">
      {primary !== null && <Badge dir="ltr">{primary}</Badge>}
      {secondary !== null && (
        <Badge variant="outline" dir="ltr">
          {secondary}
        </Badge>
      )}
    </div>
  );
}

/** The medical status pill: fit = success, injured = danger. */
export function MedicalStatusBadge({ status }: { status: MedicalStatus }) {
  const { t } = useTranslation();
  return (
    <Badge variant={status === "INJURED" ? "danger" : "success"}>
      {t(MEDICAL_STATUS_KEYS[status])}
    </Badge>
  );
}

/** The muted "משוחרר" pill of a released player. */
export function ReleasedBadge() {
  const { t } = useTranslation();
  return <Badge variant="muted">{t("squad.released")}</Badge>;
}
