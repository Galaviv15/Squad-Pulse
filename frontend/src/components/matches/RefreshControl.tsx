import { CircleCheck, LoaderCircle, RefreshCw, TriangleAlert } from "lucide-react";
import { useRef, useState, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { Button } from "@/components/ui/button";
import { formatInstant, minutesUntil } from "@/lib/matches/israelTime";
import { FAILURE_REASON_KEYS } from "@/lib/matches/labels";
import {
  COUNTDOWN_TICK_MS,
  useNow,
  useRefreshStatus,
  useTriggerRefresh,
} from "@/lib/matches/refresh";
import type { RefreshStatus } from "@/lib/matches/types";

/** A POST's own message, until the next click: the server state didn't change. */
type TriggerMessage = "unavailable" | "error";

/**
 * The matches screen's "רענון נתונים" control (KAN-68's refresh): the button and the run's status
 * line. Render it only for EDIT_FULL and up — it requests GET /matches/refresh as it mounts.
 * Hidden (the button; the status region stays, empty) while the club isn't mapped.
 *
 * The button: "סורק…" with a spinner while a run is RUNNING or the POST is in flight; disabled with
 * "in X minutes" during the cooldown; "רענון נתונים" otherwise. The status line is a role="status"
 * region that is always in the DOM, so a change (scanning, done, failed, unavailable) is announced.
 */
export function RefreshControl() {
  const { t } = useTranslation();
  const { status, endedRun } = useRefreshStatus();
  const trigger = useTriggerRefresh();
  const [message, setMessage] = useState<TriggerMessage | null>(null);
  // Drops a second click while the first POST is in flight, before the disabled state renders.
  const sending = useRef(false);

  const data = status.data;
  // While the status loads nothing shows; if it can't load, the button still works (the POST
  // answers with the real state).
  const shown = data === undefined ? status.isError : data.state !== "NOT_MAPPED";
  const busy = trigger.isPending || data?.state === "RUNNING";

  const refresh = () => {
    if (sending.current) {
      return;
    }
    sending.current = true;
    setMessage(null);
    trigger.mutate(undefined, {
      onSuccess: (answer) => {
        if (answer.outcome === "SCRAPER_UNAVAILABLE") {
          setMessage("unavailable");
        }
      },
      onError: () => setMessage("error"),
      onSettled: () => {
        sending.current = false;
      },
    });
  };

  let line: ReactNode = null;
  if (message !== null) {
    line = (
      <StatusLine tone="warning">
        {t(message === "unavailable" ? "matches.refresh.unavailable" : "matches.refresh.error")}
      </StatusLine>
    );
  } else if (data?.state === "RUNNING") {
    line = <StatusLine tone="neutral">{t("matches.refresh.scanning")}</StatusLine>;
  } else if (data?.state === "FAILED") {
    line = <FailedLine status={data} />;
  } else if (data?.state === "SUCCEEDED" && endedRun === "SUCCEEDED") {
    line = <StatusLine tone="success">{t("matches.refresh.succeeded")}</StatusLine>;
  }

  return (
    <div className="flex flex-col items-stretch gap-2 sm:items-end">
      {shown && (
        // Remounted for each new nextRefreshAt, so its countdown starts from a fresh clock.
        <RefreshButton
          key={data?.nextRefreshAt ?? ""}
          busy={busy}
          nextRefreshAt={busy ? null : (data?.nextRefreshAt ?? null)}
          onClick={refresh}
        />
      )}
      <div role="status">{line}</div>
    </div>
  );
}

/**
 * The button itself, with the cooldown's countdown: "in X minutes", rounded up, re-rendered every
 * COUNTDOWN_TICK_MS. Once the time has passed the button is simply enabled — no request then: if
 * the browser's clock runs ahead of the server's, the POST answers COOLDOWN with a fresh
 * nextRefreshAt, which shows the countdown again (no loop).
 */
function RefreshButton({
  busy,
  nextRefreshAt,
  onClick,
}: {
  busy: boolean;
  nextRefreshAt: string | null;
  onClick: () => void;
}) {
  const { t } = useTranslation();
  const now = useNow(COUNTDOWN_TICK_MS, nextRefreshAt !== null);
  const minutes = nextRefreshAt === null ? null : minutesUntil(nextRefreshAt, now);

  let content: ReactNode;
  if (busy) {
    content = (
      <>
        <LoaderCircle aria-hidden="true" className="motion-safe:animate-spin" />
        {t("matches.refresh.running")}
      </>
    );
  } else if (minutes !== null) {
    content = t("matches.refresh.cooldown", { count: minutes });
  } else {
    content = (
      <>
        <RefreshCw aria-hidden="true" />
        {t("matches.refresh.button")}
      </>
    );
  }

  return (
    <Button variant="outline" disabled={busy || minutes !== null} onClick={onClick}>
      {content}
    </Button>
  );
}

function StatusLine({
  tone,
  children,
}: {
  tone: "neutral" | "success" | "warning";
  children: ReactNode;
}) {
  const classes = {
    neutral: "text-muted-foreground",
    success: "text-success",
    warning: "bg-danger-muted text-danger rounded-md px-3 py-2",
  }[tone];
  const Icon = tone === "success" ? CircleCheck : tone === "warning" ? TriangleAlert : null;
  return (
    <div className={`flex items-start gap-2 text-[0.8125rem] font-medium ${classes}`}>
      {Icon && <Icon aria-hidden="true" className="mt-px size-4 shrink-0" />}
      <div className="flex flex-col gap-0.5">{children}</div>
    </div>
  );
}

/**
 * A failed run: when it ended and why, in plain Hebrew (never the enum), and that the data shown
 * is still from its own "last updated" time — a failed run keeps the last good data.
 */
function FailedLine({ status }: { status: RefreshStatus }) {
  const { t } = useTranslation();
  const reason = t(FAILURE_REASON_KEYS[status.failureReason ?? "NO_RESULT"]);
  return (
    <StatusLine tone="warning">
      <p>
        {status.finishedAt === null
          ? t("matches.refresh.failedNoTime", { reason })
          : t("matches.refresh.failed", { reason, time: formatInstant(status.finishedAt) })}
      </p>
      <p className="font-normal">{t("matches.refresh.failedData")}</p>
    </StatusLine>
  );
}
