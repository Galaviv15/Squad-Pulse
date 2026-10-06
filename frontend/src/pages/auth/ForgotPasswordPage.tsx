import { useRef, useState, type FormEvent } from "react";
import { useTranslation } from "react-i18next";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router";
import { AuthField } from "@/components/auth/AuthField";
import { AuthCard, AuthLayout } from "@/components/auth/AuthLayout";
import { FormAlert } from "@/components/form/FormMessage";
import { Button } from "@/components/ui/button";
import { apiJson } from "@/lib/api/client";
import { emailError, submitErrors, type FieldErrors } from "@/lib/auth/formErrors";
import {
  LOGIN_PATH,
  NEXT_PARAM,
  RESET_PASSWORD_PATH,
  safeNextPath,
  withNext,
} from "@/lib/auth/paths";
import { emailFromState, type AuthRouteState } from "./routeState";

type Field = "email";

/**
 * /app/forgot-password: asks for a code by email, then goes to the reset screen. The backend
 * answers 202 whether or not the email is registered, and so does this screen: the notice on the
 * next screen never says whether a code was really sent.
 */
export function ForgotPasswordPage() {
  const { t } = useTranslation();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const next = safeNextPath(searchParams.get(NEXT_PARAM));

  const [email, setEmail] = useState(() => emailFromState(location.state));
  const [fieldErrors, setFieldErrors] = useState<FieldErrors<Field>>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting.current) {
      return;
    }
    const trimmedEmail = email.trim();
    const errors: FieldErrors<Field> = { email: emailError(trimmedEmail) };
    setFieldErrors(errors);
    setFormError(null);
    if (errors.email) {
      return;
    }

    submitting.current = true;
    setPending(true);
    try {
      await apiJson<void>("/auth/forgot-password", {
        method: "POST",
        json: { email: trimmedEmail },
      });
      const state: AuthRouteState = { email: trimmedEmail, codeSent: true };
      void navigate(withNext(RESET_PASSWORD_PATH, next), { state });
    } catch (error) {
      submitting.current = false;
      setPending(false);
      const result = submitErrors(error, {}, { email: "auth.validation.emailInvalid" });
      setFormError(result.formError);
      setFieldErrors(result.fieldErrors);
    }
  }

  return (
    <AuthLayout>
      <AuthCard title={t("auth.forgot.title")} subtitle={t("auth.forgot.subtitle")}>
        {formError && <FormAlert>{t(formError)}</FormAlert>}
        <form noValidate onSubmit={handleSubmit} className="flex flex-col gap-4">
          <AuthField
            id="forgot-email"
            label={t("auth.fields.email")}
            type="email"
            autoComplete="email"
            dir="ltr"
            placeholder={t("auth.fields.emailPlaceholder")}
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            error={fieldErrors.email}
          />
          <Button type="submit" className="w-full font-semibold" disabled={pending}>
            {t("auth.forgot.submit")}
          </Button>
        </form>
        <Link
          to={withNext(LOGIN_PATH, next)}
          className="w-fit text-[0.8125rem] font-medium text-primary hover:underline"
        >
          {t("auth.backToLogin")}
        </Link>
      </AuthCard>
    </AuthLayout>
  );
}
