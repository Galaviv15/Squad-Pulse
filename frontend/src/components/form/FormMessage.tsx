import { CircleAlert, CircleCheck, Mail } from "lucide-react";
import type { ReactNode } from "react";

const MESSAGE_CLASSES =
  "flex items-start gap-2 rounded-md px-3.5 py-3 text-[0.8125rem] font-medium";

/** A form-level error, announced as it appears. `action` is an optional link under the text. */
export function FormAlert({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div role="alert" className={`${MESSAGE_CLASSES} bg-danger-muted text-danger`}>
      <CircleAlert aria-hidden="true" className="size-[18px] shrink-0" />
      <div className="flex flex-col gap-1">
        <p>{children}</p>
        {action}
      </div>
    </div>
  );
}

/**
 * A neutral or success notice: "code sent" (mail) or "password set" (success). Its own live region
 * by default; `live={false}` when it's rendered into a region that's always there (a region that
 * appears together with its text isn't reliably announced).
 */
export function FormNotice({
  children,
  icon,
  live = true,
}: {
  children: ReactNode;
  icon: "mail" | "success";
  live?: boolean;
}) {
  const Icon = icon === "mail" ? Mail : CircleCheck;
  return (
    <div
      role={live ? "status" : undefined}
      className={`${MESSAGE_CLASSES} bg-secondary text-secondary-foreground`}
    >
      <Icon aria-hidden="true" className="size-[18px] shrink-0" />
      <p>{children}</p>
    </div>
  );
}
