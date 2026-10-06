import { useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router";
import { AuthField } from "@/components/auth/AuthField";
import { AuthCard, AuthLayout } from "@/components/auth/AuthLayout";
import { FormAlert, FormNotice } from "@/components/auth/FormMessage";
import { Button } from "@/components/ui/button";
import { apiJson } from "@/lib/api/client";
import { authSession } from "@/lib/api/session";
import { emailError, submitErrors, type FieldErrors } from "@/lib/auth/formErrors";
import {
  FORGOT_PASSWORD_PATH,
  LOGIN_PATH,
  NEXT_PARAM,
  safeNextPath,
  withNext,
} from "@/lib/auth/paths";
import { emailFromState, stateFlag, type AuthRouteState } from "./routeState";

type Field = "email" | "code" | "newPassword" | "confirmPassword";

/** The backend's password policy (ResetPasswordRequest): 8–128 UTF-16 units, like JS length. */
const MIN_PASSWORD_LENGTH = 8;
const MAX_PASSWORD_LENGTH = 128;

/** Exactly six ASCII digits: JS's \d, like the backend's, never matches other scripts' digits. */
const CODE_PATTERN = /^\d{6}$/;

/**
 * /app/reset-password: sets a password with an emailed code. The same screen is an invited user's
 * activation (their invitation email has only the code, no link), which the login screen links to.
 * On success it logs in with the new password; PublicOnlyRoute then leaves for `next`. If that
 * login fails, the password is still set, so this goes to the login screen with a success notice,
 * never an error here.
 */
export function ResetPasswordPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const next = safeNextPath(searchParams.get(NEXT_PARAM));
  const codeSent = stateFlag(location.state, "codeSent");

  const [email, setEmail] = useState(() => emailFromState(location.state));
  const [code, setCode] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors<Field>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  // A second reset with the same code would be refused (the code is used up): one at a time.
  const submitting = useRef(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) {
      return;
    }
    const trimmedEmail = email.trim();
    const errors: FieldErrors<Field> = {
      email: emailError(trimmedEmail),
      code: codeError(code),
      newPassword: newPasswordError(newPassword),
      confirmPassword:
        confirmPassword !== newPassword ? "auth.validation.passwordsDontMatch" : undefined,
    };
    setFieldErrors(errors);
    setFormError(null);
    if (Object.values(errors).some(Boolean)) {
      return;
    }

    submitting.current = true;
    setPending(true);
    try {
      await apiJson<void>("/auth/reset-password", {
        method: "POST",
        json: { email: trimmedEmail, code, newPassword },
      });
    } catch (error) {
      submitting.current = false;
      setPending(false);
      const result = submitErrors(
        error,
        { 401: "auth.reset.invalidCode" },
        {
          email: "auth.validation.emailInvalid",
          code: "auth.validation.codeFormat",
          newPassword: "auth.validation.newPasswordLength",
        },
      );
      setFormError(result.formError);
      setFieldErrors(result.fieldErrors);
      return;
    }

    try {
      await authSession.login(trimmedEmail, newPassword);
      // Stays pending: the session is now authenticated and PublicOnlyRoute navigates away.
    } catch {
      const state: AuthRouteState = { email: trimmedEmail, passwordSet: true };
      void navigate(withNext(LOGIN_PATH, next), { replace: true, state });
    }
  }

  const forgotLinkState: AuthRouteState | undefined = email.trim()
    ? { email: email.trim() }
    : undefined;

  return (
    <AuthLayout>
      <AuthCard title={t("auth.reset.title")} subtitle={t("auth.reset.subtitle")}>
        {codeSent && !formError && <FormNotice icon="mail">{t("auth.reset.codeSent")}</FormNotice>}
        {formError && (
          <FormAlert
            action={
              formError === "auth.reset.invalidCode" && (
                <Link
                  to={withNext(FORGOT_PASSWORD_PATH, next)}
                  state={forgotLinkState}
                  className="w-fit font-semibold text-danger hover:underline"
                >
                  {t("auth.reset.requestNewCode")}
                </Link>
              )
            }
          >
            {t(formError)}
          </FormAlert>
        )}
        <form noValidate onSubmit={handleSubmit} className="flex flex-col gap-4">
          <AuthField
            id="reset-email"
            label={t("auth.fields.email")}
            type="email"
            autoComplete="email"
            dir="ltr"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={fieldErrors.email}
          />
          <AuthField
            id="reset-code"
            label={t("auth.fields.code")}
            hint={t("auth.hints.code")}
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            dir="ltr"
            placeholder={t("auth.fields.codePlaceholder")}
            className="text-base tracking-[0.4em] tabular-nums md:text-base"
            value={code}
            onChange={(event) => setCode(event.target.value)}
            error={fieldErrors.code}
          />
          <AuthField
            id="reset-new-password"
            label={t("auth.fields.newPassword")}
            hint={t("auth.hints.newPassword")}
            type="password"
            autoComplete="new-password"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
            error={fieldErrors.newPassword}
          />
          <AuthField
            id="reset-confirm-password"
            label={t("auth.fields.confirmPassword")}
            type="password"
            autoComplete="new-password"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
            error={fieldErrors.confirmPassword}
          />
          <Button type="submit" className="w-full font-semibold" disabled={pending}>
            {t("auth.reset.submit")}
          </Button>
        </form>
        <div className="flex flex-wrap items-center justify-between gap-2 text-[0.8125rem] font-medium">
          <p className="text-muted-foreground">
            {t("auth.reset.resendPrompt")}{" "}
            <Link
              to={withNext(FORGOT_PASSWORD_PATH, next)}
              state={forgotLinkState}
              className="text-primary hover:underline"
            >
              {t("auth.reset.resendLink")}
            </Link>
          </p>
          <Link to={withNext(LOGIN_PATH, next)} className="text-primary hover:underline">
            {t("auth.backToLogin")}
          </Link>
        </div>
      </AuthCard>
    </AuthLayout>
  );
}

function codeError(code: string): string | undefined {
  if (code === "") {
    return "auth.validation.codeRequired";
  }
  return CODE_PATTERN.test(code) ? undefined : "auth.validation.codeFormat";
}

function newPasswordError(password: string): string | undefined {
  // @NotBlank as well as @Size: eight spaces are refused too.
  if (password.trim() === "") {
    return "auth.validation.newPasswordRequired";
  }
  if (password.length < MIN_PASSWORD_LENGTH) {
    return "auth.validation.newPasswordTooShort";
  }
  return password.length > MAX_PASSWORD_LENGTH ? "auth.validation.newPasswordTooLong" : undefined;
}
