import { useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useSearchParams } from "react-router";
import { AuthField } from "@/components/auth/AuthField";
import { AuthCard, AuthLayout } from "@/components/auth/AuthLayout";
import { FormAlert, FormNotice } from "@/components/auth/FormMessage";
import { Button } from "@/components/ui/button";
import { authSession } from "@/lib/api/session";
import { emailError, submitErrors, type FieldErrors } from "@/lib/auth/formErrors";
import {
  FORGOT_PASSWORD_PATH,
  NEXT_PARAM,
  RESET_PASSWORD_PATH,
  safeNextPath,
  withNext,
} from "@/lib/auth/paths";
import { emailFromState, stateFlag, type AuthRouteState } from "./routeState";

type Field = "email" | "password";

/**
 * /app/login. A successful login makes the session "authenticated", and PublicOnlyRoute then
 * leaves for `next`; this screen only shows what went wrong otherwise. Arriving from a password
 * reset whose automatic login failed, it says the password was saved, with the email filled in.
 */
export function LoginPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const next = safeNextPath(searchParams.get(NEXT_PARAM));
  const passwordSet = stateFlag(location.state, "passwordSet");

  const [email, setEmail] = useState(() => emailFromState(location.state));
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors<Field>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  // State updates land after the event: this stops a second submit in the same tick.
  const submitting = useRef(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) {
      return;
    }
    const trimmedEmail = email.trim();
    const errors: FieldErrors<Field> = {
      email: emailError(trimmedEmail),
      password: password.trim() === "" ? "auth.validation.passwordRequired" : undefined,
    };
    setFieldErrors(errors);
    setFormError(null);
    if (errors.email || errors.password) {
      return;
    }

    submitting.current = true;
    setPending(true);
    try {
      await authSession.login(trimmedEmail, password);
      // Stays pending: the session is now authenticated and PublicOnlyRoute navigates away.
    } catch (error) {
      submitting.current = false;
      setPending(false);
      const errors = submitErrors(
        error,
        { 401: "auth.errors.wrongCredentials", 429: "auth.errors.tooManyAttempts" },
        { email: "auth.validation.emailInvalid", password: "auth.validation.passwordRequired" },
      );
      setFormError(errors.formError);
      setFieldErrors(errors.fieldErrors);
    }
  }

  // The forgot / reset links carry what's typed so far, so it needn't be typed again.
  const linkState: AuthRouteState | undefined = email.trim() ? { email: email.trim() } : undefined;

  return (
    <AuthLayout>
      <AuthCard title={t("auth.login.title")} subtitle={t("auth.login.subtitle")}>
        {passwordSet && !formError && (
          <FormNotice icon="success">{t("auth.login.passwordSet")}</FormNotice>
        )}
        {formError && <FormAlert>{t(formError)}</FormAlert>}
        <form noValidate onSubmit={handleSubmit} className="flex flex-col gap-4">
          <AuthField
            id="login-email"
            label={t("auth.fields.email")}
            type="email"
            autoComplete="email"
            dir="ltr"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={fieldErrors.email}
          />
          <AuthField
            id="login-password"
            label={t("auth.fields.password")}
            labelAction={
              <Link
                to={withNext(FORGOT_PASSWORD_PATH, next)}
                state={linkState}
                className="text-[0.8125rem] font-medium text-primary hover:underline"
              >
                {t("auth.login.forgotLink")}
              </Link>
            }
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            error={fieldErrors.password}
          />
          <Button type="submit" className="w-full font-semibold" disabled={pending}>
            {t("auth.login.submit")}
          </Button>
        </form>
        <div className="flex flex-col gap-1 border-t border-border pt-4 text-[0.8125rem]">
          <p className="text-muted-foreground">{t("auth.login.activationPrompt")}</p>
          <Link
            to={withNext(RESET_PASSWORD_PATH, next)}
            state={linkState}
            className="w-fit font-semibold text-primary hover:underline"
          >
            {t("auth.login.activationLink")}
          </Link>
        </div>
      </AuthCard>
    </AuthLayout>
  );
}
