import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { createMemoryRouter, type RouteObject } from "react-router";
import { RouterProvider } from "react-router/dom";
import { routes as appRoutes } from "@/app/router";
import "@/i18n/i18n";

interface RenderOptions {
  /** The URL(s) the memory router starts at; the last one is the current location. */
  initialEntries?: string[];
  /** The route table; defaults to the app's own. */
  routes?: RouteObject[];
}

/**
 * Renders a route tree the way the app does: inside a fresh QueryClient (nothing cached between
 * tests) and a memory router. Every component test goes through this. Query retries are off here,
 * and only here, so a failing request fails the test at once instead of after the retry delays.
 */
export function renderWithProviders({
  initialEntries = ["/"],
  routes = appRoutes,
}: RenderOptions = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(routes, { initialEntries });

  return {
    router,
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    ),
  };
}
