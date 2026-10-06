import { fireEvent, screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import he from "@/i18n/locales/he.json";
import { apiError, refreshRefused } from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

/** POST /auth/forgot-password answering with `answer`. Records each request's JSON body. */
function forgotAnswering(answer: () => Response) {
  const bodies: unknown[] = [];
  server.use(
    http.post("/auth/forgot-password", async ({ request }) => {
      bodies.push(await request.json());
      return answer();
    }),
  );
  return bodies;
}

async function renderForgot(path = "/app/forgot-password") {
  server.use(refreshRefused());
  const rendered = renderWithProviders({ initialEntries: [path] });
  await screen.findByRole("heading", { name: he.auth.forgot.title });
  return rendered;
}

const emailInput = () => screen.getByLabelText(he.auth.fields.email);
const submitButton = () => screen.getByRole("button", { name: he.auth.forgot.submit });

describe("ForgotPasswordPage", () => {
  it("requests a code and moves on to the reset screen with the email and a neutral notice", async () => {
    const bodies = forgotAnswering(() => new HttpResponse(null, { status: 202 }));
    const { router } = await renderForgot("/app/forgot-password?next=%2Fapp%2Fsquad");

    fireEvent.change(emailInput(), { target: { value: " coach@example.com " } });
    fireEvent.click(submitButton());

    expect(await screen.findByRole("heading", { name: he.auth.reset.title })).toBeInTheDocument();
    expect(bodies).toEqual([{ email: "coach@example.com" }]);
    expect(router.state.location.pathname + router.state.location.search).toBe(
      "/app/reset-password?next=%2Fapp%2Fsquad",
    );
    // The email travels in history state, never in the URL.
    expect(router.state.location.search).not.toContain("coach");
    expect(screen.getByLabelText(he.auth.fields.email)).toHaveValue("coach@example.com");
    expect(screen.getByRole("status")).toHaveTextContent(he.auth.reset.codeSent);
  });

  it("checks the email before sending anything", async () => {
    const bodies = forgotAnswering(() => new HttpResponse(null, { status: 202 }));
    await renderForgot();

    fireEvent.click(submitButton());

    expect(await screen.findByText(he.auth.validation.emailRequired)).toBeInTheDocument();
    expect(emailInput()).toHaveAttribute("aria-invalid", "true");
    expect(bodies).toEqual([]);
  });

  it("shows a 400 as the email's error, in Hebrew", async () => {
    forgotAnswering(() =>
      apiError(400, "Bad Request", "Validation failed", ["email: size must be between 0 and 254"]),
    );
    await renderForgot();

    fireEvent.change(emailInput(), { target: { value: "coach@example.com" } });
    fireEvent.click(submitButton());

    expect(await screen.findByText(he.auth.validation.emailInvalid)).toBeInTheDocument();
    expect(emailInput()).toHaveAccessibleDescription(he.auth.validation.emailInvalid);
  });

  it("shows the server message when there's no response, keeping the email", async () => {
    forgotAnswering(() => HttpResponse.error());
    await renderForgot();

    fireEvent.change(emailInput(), { target: { value: "coach@example.com" } });
    fireEvent.click(submitButton());

    expect(await screen.findByRole("alert")).toHaveTextContent(he.auth.errors.serverUnreachable);
    expect(emailInput()).toHaveValue("coach@example.com");
  });

  it("has an LTR email input with a placeholder, and a link back to login keeping next", async () => {
    await renderForgot("/app/forgot-password?next=%2Fapp%2Fsquad");

    expect(emailInput()).toHaveAttribute("dir", "ltr");
    expect(emailInput()).toHaveAttribute("placeholder", he.auth.fields.emailPlaceholder);
    expect(screen.getByRole("link", { name: he.auth.backToLogin })).toHaveAttribute(
      "href",
      "/app/login?next=%2Fapp%2Fsquad",
    );
  });
});
