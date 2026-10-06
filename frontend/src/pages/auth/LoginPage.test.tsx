import { fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { deferred } from "@/test/deferred";
import { accessToken, apiError, meReturns, refreshRefused } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

/** POST /auth/login answering with `answer`. Records each request's JSON body. */
function loginAnswering(answer: () => Response | Promise<Response>) {
  const bodies: unknown[] = [];
  server.use(
    http.post("/auth/login", async ({ request }) => {
      bodies.push(await request.json());
      return answer();
    }),
  );
  return bodies;
}

/** The login screen, logged out (the app-load refresh is refused), at `path`. */
async function renderLogin(path = "/app/login", initialEntries: string[] = []) {
  server.use(refreshRefused());
  const rendered = renderWithProviders({ initialEntries: [...initialEntries, path] });
  await screen.findByRole("heading", { name: he.auth.login.title });
  return rendered;
}

const emailInput = () => screen.getByLabelText(he.auth.fields.email);
const passwordInput = () => screen.getByLabelText(he.auth.fields.password);
const submitButton = () => screen.getByRole("button", { name: he.auth.login.submit });

function fillIn(email: string, password: string) {
  fireEvent.change(emailInput(), { target: { value: email } });
  fireEvent.change(passwordInput(), { target: { value: password } });
}

describe("LoginPage", () => {
  it("logs in and goes to next, replacing the login screen in the history", async () => {
    const bodies = loginAnswering(() => accessToken("t1"));
    server.use(meReturns());
    const { router } = await renderLogin("/app/login?next=%2Fapp%3Fview%3Dall", ["/before"]);

    fillIn("  coach@example.com ", "correct horse");
    fireEvent.click(submitButton());

    expect(
      await screen.findByRole("heading", { level: 1, name: he.nav.dashboard }),
    ).toBeInTheDocument();
    expect(router.state.location.pathname + router.state.location.search).toBe("/app?view=all");
    expect(router.state.historyAction).toBe("REPLACE");
    // The email is sent trimmed; the password exactly as typed.
    expect(bodies).toEqual([{ email: "coach@example.com", password: "correct horse" }]);

    // Back skips the login screen: it was replaced.
    await router.navigate(-1);
    expect(router.state.location.pathname).toBe("/before");
  });

  it("goes to /app without next", async () => {
    loginAnswering(() => accessToken("t1"));
    server.use(meReturns());
    const { router } = await renderLogin();

    fillIn("coach@example.com", "pw");
    fireEvent.click(submitButton());

    await screen.findByRole("heading", { level: 1, name: he.nav.dashboard });
    expect(router.state.location.pathname).toBe("/app");
  });

  it.each([
    [
      "401",
      () => apiError(401, "Unauthorized", "Invalid email or password"),
      he.auth.errors.wrongCredentials,
    ],
    [
      "429",
      () => apiError(429, "Too Many Requests", "Too many failed login attempts, try again later"),
      he.auth.errors.tooManyAttempts,
    ],
    [
      "502 without a body",
      () => new HttpResponse(null, { status: 502 }),
      he.auth.errors.serverUnreachable,
    ],
    ["no response", () => HttpResponse.error(), he.auth.errors.serverUnreachable],
    [
      "an unexpected 403",
      () => apiError(403, "Forbidden", "Access denied"),
      he.auth.errors.unexpected,
    ],
  ])(
    "shows the Hebrew message for a %s and keeps the form filled",
    async (_name, answer, message) => {
      loginAnswering(answer);
      await renderLogin();

      fillIn("coach@example.com", "wrong");
      fireEvent.click(submitButton());

      expect(await screen.findByRole("alert")).toHaveTextContent(message);
      expect(emailInput()).toHaveValue("coach@example.com");
      expect(passwordInput()).toHaveValue("wrong");
      expect(submitButton()).toBeEnabled();
    },
  );

  it("checks the fields before sending anything", async () => {
    const bodies = loginAnswering(() => accessToken("t1"));
    await renderLogin();

    fillIn("   ", "   ");
    fireEvent.click(submitButton());

    expect(await screen.findByText(he.auth.validation.emailRequired)).toBeInTheDocument();
    expect(screen.getByText(he.auth.validation.passwordRequired)).toBeInTheDocument();
    expect(emailInput()).toHaveAttribute("aria-invalid", "true");
    expect(emailInput()).toHaveAccessibleDescription(he.auth.validation.emailRequired);
    expect(passwordInput()).toHaveAccessibleDescription(he.auth.validation.passwordRequired);
    expect(bodies).toEqual([]);
  });

  it("shows a 400's field error in Hebrew, never the backend's text", async () => {
    loginAnswering(() =>
      apiError(400, "Bad Request", "Validation failed", ["email: size must be between 0 and 254"]),
    );
    await renderLogin();

    fillIn("coach@example.com", "pw");
    fireEvent.click(submitButton());

    expect(await screen.findByText(he.auth.validation.emailInvalid)).toBeInTheDocument();
    expect(emailInput()).toHaveAttribute("aria-invalid", "true");
    expect(screen.queryByText(/size must be/)).toBeNull();
  });

  it("sends one login for a double submit, and disables the button meanwhile", async () => {
    const gate = deferred();
    const bodies = loginAnswering(async () => {
      await gate.promise;
      return apiError(401, "Unauthorized", "Invalid email or password");
    });
    await renderLogin();

    fillIn("coach@example.com", "pw");
    const form = submitButton().closest("form")!;
    fireEvent.submit(form);
    fireEvent.submit(form);
    expect(submitButton()).toBeDisabled();
    await waitFor(() => expect(bodies).toHaveLength(1));
    gate.resolve();

    await screen.findByRole("alert");
    expect(bodies).toHaveLength(1);
  });

  it("links to forgot password and to code entry, keeping next and the email typed so far", async () => {
    server.use(http.post("/auth/forgot-password", () => new HttpResponse(null, { status: 202 })));
    const { router } = await renderLogin("/app/login?next=%2Fapp%2Fsquad");

    expect(screen.getByRole("link", { name: he.auth.login.forgotLink })).toHaveAttribute(
      "href",
      "/app/forgot-password?next=%2Fapp%2Fsquad",
    );
    expect(screen.getByRole("link", { name: he.auth.login.activationLink })).toHaveAttribute(
      "href",
      "/app/reset-password?next=%2Fapp%2Fsquad",
    );
    expect(screen.getByText(he.auth.login.activationPrompt)).toBeInTheDocument();

    fireEvent.change(emailInput(), { target: { value: "new@example.com" } });
    fireEvent.click(screen.getByRole("link", { name: he.auth.login.activationLink }));

    await screen.findByRole("heading", { name: he.auth.reset.title });
    expect(router.state.location.pathname).toBe("/app/reset-password");
    expect(screen.getByLabelText(he.auth.fields.email)).toHaveValue("new@example.com");
  });

  it("names every input and keeps the email an LTR island", async () => {
    await renderLogin();

    expect(emailInput()).toHaveAttribute("dir", "ltr");
    expect(emailInput()).toHaveAttribute("type", "email");
    expect(emailInput()).toHaveAttribute("autocomplete", "email");
    expect(passwordInput()).toHaveAttribute("type", "password");
    expect(passwordInput()).toHaveAttribute("autocomplete", "current-password");
  });
});
