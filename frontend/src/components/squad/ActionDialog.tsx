import { useRef, type FormEvent, type ReactNode } from "react";
import { useTranslation } from "react-i18next";
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { FinalFocus } from "./playerDialogs";

export interface ActionDialogProps {
  open: boolean;
  /**
   * Asked to close: "ביטול" or Escape (an alert dialog ignores backdrop clicks). Never called while
   * `pending`, so a request's answer never lands on a closed dialog.
   */
  onClose(): void;
  /** The dialog has finished closing (after its exit animation): the host may forget its target. */
  onClosed?(): void;
  /** Where focus goes when it closes (default: the element focused when it opened). */
  finalFocus?: FinalFocus;
  /** The request is running: the confirm button shows `pendingLabel`, nothing closes the dialog. */
  pending: boolean;
  title: string;
  /** The text under the title (the dialog's aria-describedby); none for a dialog that's a field. */
  description?: string;
  /** Fields, between the description and the error. */
  children?: ReactNode;
  /** The failure, as a FormAlert, shown inside the dialog. */
  error?: ReactNode;
  confirmLabel: string;
  pendingLabel: string;
  destructive?: boolean;
  /** The confirm button or Enter in a field; called once at a time, however often it's pressed. */
  onConfirm(): Promise<unknown>;
}

/**
 * A confirmation dialog: shadcn's alert-dialog (Base UI AlertDialog: role="alertdialog", modal,
 * focus trapped, labelled by the title and described by the description, no backdrop dismissal),
 * controlled by its host. The content is a form, so Enter in a field confirms. The footer is
 * "ביטול" then the confirm button, packed to the row's end: in RTL the pair sits at the far (left)
 * end, the confirm button outermost; on a phone they stack, the confirm button on top. Initial focus
 * is the first focusable element: the field if there's one, otherwise "ביטול", the safe choice.
 */
export function ActionDialog({
  open,
  onClose,
  onClosed,
  finalFocus,
  pending,
  title,
  description,
  children,
  error,
  confirmLabel,
  pendingLabel,
  destructive = false,
  onConfirm,
}: ActionDialogProps) {
  const { t } = useTranslation();
  // `pending` reaches the button a render later: this drops a second click in the same tick.
  const running = useRef(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (running.current) {
      return;
    }
    running.current = true;
    try {
      await onConfirm();
    } finally {
      running.current = false;
    }
  }

  return (
    <AlertDialog
      open={open}
      onOpenChange={(next) => {
        if (!next && !pending && !running.current) {
          onClose();
        }
      }}
      onOpenChangeComplete={(isOpen) => {
        if (!isOpen) {
          onClosed?.();
        }
      }}
    >
      <AlertDialogContent finalFocus={finalFocus} className="data-[size=default]:sm:max-w-md">
        <form noValidate onSubmit={(event) => void handleSubmit(event)} className="grid gap-4">
          <AlertDialogHeader>
            <AlertDialogTitle className="text-lg font-semibold">{title}</AlertDialogTitle>
            {description !== undefined && (
              <AlertDialogDescription>{description}</AlertDialogDescription>
            )}
          </AlertDialogHeader>
          {children}
          {error}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={pending}>{t("squad.dialog.cancel")}</AlertDialogCancel>
            <Button
              type="submit"
              disabled={pending}
              className={cn(
                destructive && "bg-destructive text-destructive-foreground hover:bg-destructive/90",
              )}
            >
              {pending ? pendingLabel : confirmLabel}
            </Button>
          </AlertDialogFooter>
        </form>
      </AlertDialogContent>
    </AlertDialog>
  );
}
