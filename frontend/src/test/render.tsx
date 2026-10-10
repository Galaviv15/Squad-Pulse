import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { StrictMode, type ReactNode } from "react";
import { createMemoryRouter, type RouteObject } from "react-router";
import { RouterProvider } from "react-router/dom";
import { routes as appRoutes } from "@/app/router";
import { DirectionProvider } from "@/components/ui/direction";
import { authSession } from "@/lib/api/session";
import { bindSessionToQueryClient } from "@/lib/auth/bindSessionToQueryClient";
import { bindSessionToSquadReturnPath } from "@/lib/squad/squadReturnPath";
import "@/i18n/i18n";

interface RenderOptions {
  /** The URL(s) the memory router starts at; the last one is the current location. */
  initialEntries?: string[];
  /** The route table; defaults to the app's own. */
  routes?: RouteObject[];
  /** Renders inside <StrictMode>, as main.tsx does: effects mount, unmount and mount again. */
  strict?: boolean;
}

/**
 * Renders a route tree the way the app does: in RTL for Base UI, inside a fresh QueryClient
 * (nothing cached between tests) that the app session empties when it ends, as in main.tsx, and a
 * memory router; the remembered squad URL is forgotten when the session ends, as in main.tsx too.
 * Every component test goes through this. Query retries are off here, and only
 * here, so a failing request fails the test at once instead of after the retry delays. The
 * session bindings are dropped with the session by the reset after each test (src/test/setup.ts).
 */
export function renderWithProviders({
  initialEntries = ["/"],
  routes = appRoutes,
  strict = false,
}: RenderOptions = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  bindSessionToQueryClient(authSession, queryClient);
  bindSessionToSquadReturnPath(authSession);
  const router = createMemoryRouter(routes, { initialEntries });

  const tree: ReactNode = (
    <DirectionProvider direction="rtl">
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </DirectionProvider>
  );

  return {
    router,
    queryClient,
    ...render(strict ? <StrictMode>{tree}</StrictMode> : tree),
  };
}
