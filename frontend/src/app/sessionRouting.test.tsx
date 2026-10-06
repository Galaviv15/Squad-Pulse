import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import he from "@/i18n/locales/he.json";
import {
  authSession,
  LOGOUT_MESSAGE,
  resetAuthSessionForTests,
  type LogoutChannel,
} from "@/lib/api/session";
import { resetSessionBootstrap } from "@/lib/auth/bootstrap";
import { deferred } from "@/test/deferred";
import {
  accessToken,
  authenticationRequired,
  currentUserBody,
  loggedIn,
  loginReturns,
  meReturns,
  refreshRefused,
  refreshReturns,
} from "@/test/msw/auth";
import { server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";

/** The shell's page title at /app: what a logged-in user sees. */
const dashboardHeading = { level: 1, name: he.nav.dashboard } as const;
const appHeading = () => screen.queryByRole("heading", dashboardHeading);
const loginHeading = () => screen.queryByRole("heading", { name: he.auth.login.title });

function currentUrl(router: { state: { location: { pathname: string; search: string } } }) {
  return router.state.location.pathname + router.state.location.search;
}

/** POST /auth/refresh answering with `answer`. Counts the calls. */
function refreshCounted(answer: () => Response | Promise<Response>) {
  const calls = { count: 0 };
  server.use(
    http.post("/auth/refresh", () => {
      calls.count++;
      return answer();
    }),
  );
  return calls;
}

/** POST /auth/logout answering with `answer` (204 by default). Counts the calls. */
function logoutCounted(answer: () => Response | Promise<Response> = () => noContent()) {
  const calls = { count: 0 };
  server.use(
    http.post("/auth/logout", () => {
      calls.count++;
      return answer();
    }),
  );
  return calls;
}

const noContent = () => new HttpResponse(null, { status: 204 });

class FakeLogoutChannel implements LogoutChannel {
  onmessage: ((event: MessageEvent) => void) | null = null;
  readonly posted: unknown[] = [];
  postMessage(message: unknown) {
    this.posted.push(message);
  }
  close() {}
  receive(data: unknown) {
    this.onmessage?.(new MessageEvent("message", { data }));
  }
}

describe("app-load bootstrap", () => {
  it("shows only the loading screen until the refresh and /me have answered", async () => {
    const refreshGate = deferred();
    const meGate = deferred();
    const requested: string[] = [];
    server.use(
      http.post("/auth/refresh", async () => {
        requested.push("refresh");
        await refreshGate.promise;
        return accessToken("t1");
      }),
      http.get("/auth/users/me", async () => {
        requested.push("me");
        await meGate.promise;
        return HttpResponse.json(currentUserBody());
      }),
    );
    renderWithProviders({ initialEntries: ["/app"] });

    expect(await screen.findByRole("status")).toHaveTextContent(he.auth.loading);
    await waitFor(() => expect(requested).toEqual(["refresh"]));
    expect(loginHeading()).toBeNull();
    expect(appHeading()).toBeNull();

    refreshGate.resolve();
    await waitFor(() => expect(requested).toEqual(["refresh", "me"]));
    expect(screen.getByRole("status")).toHaveTextContent(he.auth.loading);
    expect(loginHeading()).toBeNull();
    expect(appHeading()).toBeNull();

    meGate.resolve();
    expect(await screen.findByRole("heading", dashboardHeading)).toBeInTheDocument();
    expect(loginHeading()).toBeNull();
  });

  it("sends a first visit (refresh 401) to login, with next", async () => {
    server.use(refreshRefused());
    const { router } = renderWithProviders({ initialEntries: ["/app/squad?position=GK"] });

    expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
    expect(currentUrl(router)).toBe("/app/login?next=%2Fapp%2Fsquad%3Fposition%3DGK");
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("sends a first visit to /app to login without next", async () => {
    server.use(refreshRefused());
    const { router } = renderWithProviders({ initialEntries: ["/app"] });

    await screen.findByRole("heading", { name: he.auth.login.title });
    expect(currentUrl(router)).toBe("/app/login");
  });

  it("checks the session before an unknown /app path shows not-found", async () => {
    server.use(refreshRefused());
    const { router } = renderWithProviders({ initialEntries: ["/app/nope"] });

    await screen.findByRole("heading", { name: he.auth.login.title });
    expect(currentUrl(router)).toBe("/app/login?next=%2Fapp%2Fnope");
  });

  it.each([
    ["a 502 without a body", () => new HttpResponse(null, { status: 502 })],
    ["no response", () => HttpResponse.error()],
    ["a 200 without a token", () => HttpResponse.json({})],
  ])(
    "shows the server-error screen, never the login form, when the refresh gets %s; retry refreshes again",
    async (_name, answer) => {
      let failing = true;
      const refresh = refreshCounted(() => (failing ? answer() : accessToken("t1")));
      server.use(meReturns());
      renderWithProviders({ initialEntries: ["/app"] });

      expect(
        await screen.findByRole("heading", { name: he.auth.serverError.title }),
      ).toBeInTheDocument();
      expect(screen.getByText(he.auth.serverError.body)).toBeInTheDocument();
      expect(loginHeading()).toBeNull();
      expect(authSession.getStatus()).toBe("unknown");

      failing = false;
      fireEvent.click(screen.getByRole("button", { name: he.auth.serverError.retry }));

      expect(await screen.findByRole("heading", dashboardHeading)).toBeInTheDocument();
      expect(refresh.count).toBe(2);
    },
  );

  it("shows the server-error screen when /me fails after the refresh; retry fetches /me again", async () => {
    let meFails = true;
    const refresh = refreshCounted(() => accessToken("t1"));
    server.use(
      http.get("/auth/users/me", () =>
        meFails
          ? new HttpResponse("<html>oops</html>", { status: 500 })
          : HttpResponse.json(currentUserBody()),
      ),
    );
    renderWithProviders({ initialEntries: ["/app"] });

    expect(
      await screen.findByRole("heading", { name: he.auth.serverError.title }),
    ).toBeInTheDocument();
    expect(appHeading()).toBeNull();

    meFails = false;
    fireEvent.click(screen.getByRole("button", { name: he.auth.serverError.retry }));

    expect(await screen.findByRole("heading", dashboardHeading)).toBeInTheDocument();
    expect(refresh.count).toBe(1);
  });

  it("sends exactly one refresh on app load, also under StrictMode", async () => {
    const gate = deferred();
    const refresh = refreshCounted(async () => {
      await gate.promise;
      return accessToken("t1");
    });
    server.use(meReturns());
    renderWithProviders({ initialEntries: ["/app"], strict: true });

    await waitFor(() => expect(refresh.count).toBe(1));
    gate.resolve();

    expect(await screen.findByRole("heading", dashboardHeading)).toBeInTheDocument();
    expect(refresh.count).toBe(1);
  });

  it("doesn't refresh again when the app is mounted again after a failed bootstrap", async () => {
    const refresh = refreshCounted(() => new HttpResponse(null, { status: 502 }));
    const first = renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { name: he.auth.serverError.title });

    first.unmount();
    renderWithProviders({ initialEntries: ["/app"] });

    expect(
      await screen.findByRole("heading", { name: he.auth.serverError.title }),
    ).toBeInTheDocument();
    expect(refresh.count).toBe(1);
  });

  it("sends a user whose /me is refused (deactivated) to login, after trying a refresh", async () => {
    // The app-load refresh succeeds; the one apiFetch tries after /me's 401 is refused.
    const refresh = refreshCounted(() =>
      refresh.count === 1 ? accessToken("t1") : authenticationRequired(),
    );
    server.use(http.get("/auth/users/me", () => authenticationRequired()));
    const { router } = renderWithProviders({ initialEntries: ["/app"] });

    expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
    expect(refresh.count).toBe(2);
    expect(currentUrl(router)).toBe("/app/login");
    expect(authSession.getStatus()).toBe("unauthenticated");
  });
});

describe("public auth routes", () => {
  it.each([
    ["/app/login", "/app"],
    ["/app/login?next=%2Fapp%3Fview%3Dall", "/app?view=all"],
    ["/app/login?next=%2F%2Fexample.com", "/app"],
    ["/app/forgot-password", "/app"],
    ["/app/reset-password", "/app"],
  ])("send a logged-in user at %s on to %s", async (start, landing) => {
    server.use(...loggedIn());
    const { router } = renderWithProviders({ initialEntries: [start] });

    expect(await screen.findByRole("heading", dashboardHeading)).toBeInTheDocument();
    expect(currentUrl(router)).toBe(landing);
    expect(router.state.historyAction).toBe("REPLACE");
  });
});

describe("the session ending", () => {
  async function renderLoggedIn(path = "/app?view=all#top") {
    server.use(...loggedIn());
    const rendered = renderWithProviders({ initialEntries: [path] });
    await screen.findByRole("heading", dashboardHeading);
    rendered.queryClient.setQueryData(["squad", "players"], ["p1"]);
    return rendered;
  }

  it("by itself sends the user to login with next = where they were, and empties the cache", async () => {
    const { router, queryClient } = await renderLoggedIn();

    act(() => authSession.clear());

    expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
    expect(currentUrl(router)).toBe("/app/login?next=%2Fapp%3Fview%3Dall%23top");
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it("by an explicit logout goes to login without next, and empties the cache", async () => {
    const gate = deferred();
    const logout = logoutCounted(async () => {
      await gate.promise;
      return noContent();
    });
    const { router, queryClient } = await renderLoggedIn();

    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));
    await waitFor(() => expect(logout.count).toBe(1));
    expect(screen.getByRole("button", { name: he.shell.logout })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));
    gate.resolve();

    expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
    expect(currentUrl(router)).toBe("/app/login");
    expect(logout.count).toBe(1);
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it("by an explicit logout that can't reach the server still logs out locally", async () => {
    logoutCounted(() => HttpResponse.error());
    const { router } = await renderLoggedIn();

    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));

    expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
    expect(currentUrl(router)).toBe("/app/login");
    expect(authSession.getStatus()).toBe("unauthenticated");
  });

  it("by an explicit logout tells the other tabs once", async () => {
    const channel = new FakeLogoutChannel();
    resetAuthSessionForTests({ logoutChannel: channel });
    logoutCounted();
    await renderLoggedIn();

    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));

    await screen.findByRole("heading", { name: he.auth.login.title });
    expect(channel.posted).toEqual([LOGOUT_MESSAGE]);
  });

  it("by a logout in another tab sends this one to login with next, without a POST /auth/logout", async () => {
    const channel = new FakeLogoutChannel();
    resetAuthSessionForTests({ logoutChannel: channel });
    const logout = logoutCounted();
    const { router, queryClient } = await renderLoggedIn();

    act(() => channel.receive(LOGOUT_MESSAGE));

    expect(await screen.findByRole("heading", { name: he.auth.login.title })).toBeInTheDocument();
    expect(currentUrl(router)).toBe("/app/login?next=%2Fapp%3Fview%3Dall%23top");
    expect(logout.count).toBe(0);
    expect(channel.posted).toEqual([]);
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });
});

describe("storage", () => {
  it("writes nothing to localStorage or sessionStorage through login, a reload and logout", async () => {
    const setItem = vi.spyOn(Storage.prototype, "setItem");
    server.use(refreshRefused(), loginReturns("t1"), meReturns());
    const first = renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", { name: he.auth.login.title });
    fireEvent.change(screen.getByLabelText(he.auth.fields.email), {
      target: { value: "coach@example.com" },
    });
    fireEvent.change(screen.getByLabelText(he.auth.fields.password), { target: { value: "pw" } });
    fireEvent.click(screen.getByRole("button", { name: he.auth.login.submit }));
    await screen.findByRole("heading", dashboardHeading);

    // A reload: a fresh page, whose session starts unknown and refreshes with the cookie.
    first.unmount();
    resetAuthSessionForTests();
    resetSessionBootstrap();
    server.use(refreshReturns("t2"));
    renderWithProviders({ initialEntries: ["/app"] });
    await screen.findByRole("heading", dashboardHeading);

    logoutCounted();
    fireEvent.click(screen.getByRole("button", { name: he.shell.logout }));
    await screen.findByRole("heading", { name: he.auth.login.title });

    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    setItem.mockRestore();
  });
});
