import { createBrowserRouter, redirect, type RouteObject } from "react-router";
import { LoadingScreen } from "@/components/auth/StatusScreens";
import { DashboardPage } from "@/pages/DashboardPage";
import { NotFoundInShellPage, NotFoundPage } from "@/pages/NotFoundPage";
import { ForgotPasswordPage } from "@/pages/auth/ForgotPasswordPage";
import { LoginPage } from "@/pages/auth/LoginPage";
import { ResetPasswordPage } from "@/pages/auth/ResetPasswordPage";
import { AppShell } from "./AppShell";
import type { RouteHandle } from "./pageTitle";
import { PublicOnlyRoute } from "./PublicOnlyRoute";
import { RequireAuth } from "./RequireAuth";
import { SessionGate } from "./SessionGate";

/**
 * The app's route table. Every SPA route lives under /app: the backend owns /auth, /squad, /clubs
 * and /users (proxied in dev), so a browser reload on an SPA URL must never use one of those.
 *
 * Everything under /app waits for the session (SessionGate). The public auth screens are under
 * PublicOnlyRoute (a logged-in user is sent on into the app); everything else is under
 * RequireAuth, including /app's own not-found page, so an unknown /app path asks for a login
 * first. RequireAuth's only child is the app shell, the layout of every protected page; each of
 * those declares its title in its handle (RouteHandle), which the shell shows as the <h1>.
 *
 * A page with heavy code is loaded lazily (route `lazy`), so the main bundle stays small; its
 * handle stays static.
 */
export const routes: RouteObject[] = [
  { path: "/", loader: () => redirect("/app") },
  {
    path: "/app",
    element: <SessionGate />,
    // Shown on a page load whose route is lazy, until its code has loaded.
    hydrateFallbackElement: <LoadingScreen />,
    children: [
      {
        element: <PublicOnlyRoute />,
        children: [
          { path: "login", element: <LoginPage /> },
          { path: "forgot-password", element: <ForgotPasswordPage /> },
          { path: "reset-password", element: <ResetPasswordPage /> },
        ],
      },
      {
        element: <RequireAuth />,
        children: [
          {
            element: <AppShell />,
            children: [
              {
                index: true,
                element: <DashboardPage />,
                handle: { titleKey: "nav.dashboard" } satisfies RouteHandle,
              },
              // Lazy: the squad's code (Base UI's select among it) is its own chunk, loaded on the
              // first visit. The handle stays here, so the title never waits for it. The function
              // form, not the per-property object: React Router clears an object's loaders once
              // they've run, on the route table shared by every router created from it (tests).
              {
                path: "squad",
                lazy: async () => ({
                  Component: (await import("@/pages/squad/SquadPage")).SquadPage,
                }),
                handle: { titleKey: "nav.squad" } satisfies RouteHandle,
              },
              // A static segment ranks above a dynamic one, so "new" is never a :playerId.
              {
                path: "squad/new",
                lazy: async () => ({
                  Component: (await import("@/pages/squad/PlayerStubPages")).NewPlayerPage,
                }),
                handle: { titleKey: "squad.addPlayer" } satisfies RouteHandle,
              },
              {
                path: "squad/:playerId",
                lazy: async () => ({
                  Component: (await import("@/pages/squad/PlayerStubPages")).PlayerCardPage,
                }),
                handle: { titleKey: "squad.playerCard" } satisfies RouteHandle,
              },
              {
                path: "*",
                element: <NotFoundInShellPage />,
                handle: { titleKey: "notFound.title" } satisfies RouteHandle,
              },
            ],
          },
        ],
      },
    ],
  },
  { path: "*", element: <NotFoundPage /> },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
