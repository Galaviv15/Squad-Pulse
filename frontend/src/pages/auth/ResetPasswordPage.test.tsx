import { fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { deferred } from "@/test/deferred";
import { accessToken, apiError, meReturns, refreshRefused } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

/** A POST handler answering with `answer`, recording each request's JSON body. */
function postAnswering(path: string, answer: () => Response | Promise<Response>) {
  const bodies: unknown[] = [];
  server.use(
    http.post(path, async ({ request }) => {
      bodies.push(await request.json());
      return answer();
    }),
  );
  return bodies;
}

const noContent = () => new HttpResponse(null, { status: 204 });

async function renderReset(path = "/app/reset-password") {
  server.use(refreshRefused());
  const rendered = renderWithProviders({ initialEntries: [path] });
  await screen.findByRole("heading", { name: he.auth.reset.title });
  return rendered;
}

const input = (label: string) => screen.getByLabelText(label);
const submitButton = () => screen.getByRole("button", { name: he.auth.reset.submit });

interface ResetValues {
  email?: string;
  code?: string;
  newPassword?: string;
  /** Defaults to newPassword. */
  confirmPassword?: string;
}

function fillIn({
  email = "new@example.com",
  code = "123456",
  newPassword = "correct horse",
  confirmPassword = newPassword,
}: ResetValues = {}) {
  fireEvent.change(input(he.auth.fields.email), { target: { value: email } });
  fireEvent.change(input(he.auth.fields.code), { target: { value: code } });
  fireEvent.change(input(he.auth.fields.newPassword), { target: { value: newPassword } });
  fireEvent.change(input(he.auth.fields.confirmPassword), { target: { value: confirmPassword } });
}

describe("ResetPasswordPage", () => {
  it("sets the password, logs in with it and goes to next", async () => {
    const resets = postAnswering("/auth/reset-password", noContent);
    const logins = postAnswering("/auth/login", () => accessToken("t1"));
    server.use(meReturns());
    const { router } = await renderReset("/app/reset-password?next=%2Fapp%3Fview%3Dall");

    fillIn({ email: " new@example.com " });
    fireEvent.click(submitButton());

    expect(
      await screen.findByRole("heading", { level: 1, name: he.nav.dashboard }),
    ).toBeInTheDocument();
    // Exactly these fields: the confirmation never leaves the browser.
    expect(resets).toEqual([
      { email: "new@example.com", code: "123456", newPassword: "correct horse" },
    ]);
    expect(logins).toEqual([{ email: "new@example.com", password: "correct horse" }]);
    expect(router.state.location.pathname + router.state.location.search).toBe("/app?view=all");
  });

  it("goes to /app without next", async () => {
    postAnswering("/auth/reset-password", noContent);
    postAnswering("/auth/login", () => accessToken("t1"));
    server.use(meReturns());
    const { router } = await renderReset();

    fillIn();
    fireEvent.click(submitButton());

    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
    expect(router.state.location.pathname).toBe("/app");
  });

  it.each([
    ["a 429", () => apiError(429, "Too Many Requests", "Too many failed login attempts")],
    ["no response", () => HttpResponse.error()],
  ])(
    "sends the user to login with a success notice when the login after the reset gets %s",
    async (_name, loginAnswer) => {
      postAnswering("/auth/reset-password", noContent);
      postAnswering("/auth/login", loginAnswer);
      const { router } = await renderReset("/app/reset-password?next=%2Fapp%2Fsquad");

      fillIn();
      fireEvent.click(submitButton());

      expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
      expect(router.state.location.pathname + router.state.location.search).toBe(
        "/app/login?next=%2Fapp%2Fsquad",
      );
      expect(router.state.historyAction).toBe("REPLACE");
      expect(screen.getByRole("status")).toHaveTextContent(he.auth.login.passwordSet);
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.getByLabelText(he.auth.fields.email)).toHaveValue("new@example.com");
    },
  );

  it("says the code is wrong or expired, with a link to a new code that keeps the email", async () => {
    postAnswering("/auth/reset-password", () =>
      apiError(401, "Unauthorized", "Invalid or expired code"),
    );
    await renderReset();

    fillIn();
    fireEvent.click(submitButton());

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(he.auth.reset.invalidCode);
    fireEvent.click(screen.getByRole("link", { name: he.auth.reset.requestNewCode }));

    expect(await screen.findByRole("heading", { name: he.auth.forgot.title })).toBeInTheDocument();
    expect(screen.getByLabelText(he.auth.fields.email)).toHaveValue("new@example.com");
  });

  it("shows a 400 on the field it names, in Hebrew", async () => {
    // The backend's real format for @Pattern(regexp = "\\d{6}") on `code`.
    postAnswering("/auth/reset-password", () =>
      apiError(400, "Bad Request", "Validation failed", ['code: must match "\\d{6}"']),
    );
    await renderReset();

    fillIn();
    fireEvent.click(submitButton());

    expect(await screen.findByText(he.auth.validation.codeFormat)).toBeInTheDocument();
    expect(input(he.auth.fields.code)).toHaveAttribute("aria-invalid", "true");
    expect(input(he.auth.fields.code)).toHaveAccessibleDescription(he.auth.validation.codeFormat);
    expect(screen.queryByText(/must match/)).toBeNull();
  });

  it("shows the server message on a 5xx, keeping the form filled", async () => {
    postAnswering("/auth/reset-password", () => new HttpResponse(null, { status: 502 }));
    await renderReset();

    fillIn();
    fireEvent.click(submitButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(he.auth.errors.serverUnreachable);
    expect(input(he.auth.fields.code)).toHaveValue("123456");
  });

  it.each([
    ["a 5-digit code", { code: "12345" }, he.auth.fields.code, he.auth.validation.codeFormat],
    ["a non-digit code", { code: "12345a" }, he.auth.fields.code, he.auth.validation.codeFormat],
    ["no code", { code: "" }, he.auth.fields.code, he.auth.validation.codeRequired],
    [
      "a 7-character password",
      { newPassword: "1234567" },
      he.auth.fields.newPassword,
      he.auth.validation.newPasswordTooShort,
    ],
    [
      "a 129-character password",
      { newPassword: "x".repeat(129) },
      he.auth.fields.newPassword,
      he.auth.validation.newPasswordTooLong,
    ],
    [
      "a blank password",
      { newPassword: " ".repeat(8) },
      he.auth.fields.newPassword,
      he.auth.validation.newPasswordRequired,
    ],
    [
      "a confirmation that doesn't match",
      { confirmPassword: "correct horsE" },
      he.auth.fields.confirmPassword,
      he.auth.validation.passwordsDontMatch,
    ],
    ["no email", { email: " " }, he.auth.fields.email, he.auth.validation.emailRequired],
  ])("sends nothing for %s", async (_name, values, field, message) => {
    const resets = postAnswering("/auth/reset-password", noContent);
    await renderReset();

    fillIn(values);
    fireEvent.click(submitButton());

    expect(await screen.findByText(message)).toBeInTheDocument();
    expect(input(field)).toHaveAccessibleDescription(message);
    expect(resets).toEqual([]);
  });

  it("accepts a 128-character password", async () => {
    const resets = postAnswering("/auth/reset-password", () =>
      apiError(401, "Unauthorized", "Invalid or expired code"),
    );
    await renderReset();

    fillIn({ newPassword: "x".repeat(128) });
    fireEvent.click(submitButton());

    await screen.findByRole("alert");
    expect(resets).toHaveLength(1);
  });

  it("sends one reset for a double submit, so the code isn't used up by a second one", async () => {
    const gate = deferred();
    const resets = postAnswering("/auth/reset-password", async () => {
      await gate.promise;
      return apiError(401, "Unauthorized", "Invalid or expired code");
    });
    await renderReset();

    fillIn();
    const form = submitButton().closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(submitButton()).toBeDisabled();
    await waitFor(() => expect(resets).toHaveLength(1));
    gate.resolve();

    await screen.findByRole("alert");
    expect(resets).toHaveLength(1);
  });

  it("has the code input a one-time code needs, as an LTR island", async () => {
    await renderReset();

    const code = input(he.auth.fields.code);
    expect(code).toHaveAttribute("dir", "ltr");
    expect(code).toHaveAttribute("inputmode", "numeric");
    expect(code).toHaveAttribute("autocomplete", "one-time-code");
    expect(code).toHaveAttribute("maxlength", "6");
    expect(code).toHaveAccessibleDescription(he.auth.hints.code);
    expect(input(he.auth.fields.email)).toHaveAttribute("dir", "ltr");
    expect(input(he.auth.fields.newPassword)).toHaveAttribute("autocomplete", "new-password");
    expect(input(he.auth.fields.newPassword)).toHaveAccessibleDescription(
      he.auth.hints.newPassword,
    );
  });

  it("links to a new code and back to login, keeping next", async () => {
    await renderReset("/app/reset-password?next=%2Fapp%2Fsquad");

    expect(screen.getByText(he.auth.reset.resendPrompt, { exact: false })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: he.auth.reset.resendLink })).toHaveAttribute(
      "href",
      "/app/forgot-password?next=%2Fapp%2Fsquad",
    );
    expect(screen.getByRole("link", { name: he.auth.backToLogin })).toHaveAttribute(
      "href",
      "/app/login?next=%2Fapp%2Fsquad",
    );
  });
});
