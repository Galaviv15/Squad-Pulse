import { createBrowserRouter, redirect, type RouteObject } from "react-router";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { PlaceholderPage } from "@/pages/PlaceholderPage";
import { ForgotPasswordPage } from "@/pages/auth/ForgotPasswordPage";
import { LoginPage } from "@/pages/auth/LoginPage";
import { ResetPasswordPage } from "@/pages/auth/ResetPasswordPage";
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
 * first. The app shell (KAN-48) becomes RequireAuth's layout.
 */
export const routes: RouteObject[] = [
  { path: "/", loader: () => redirect("/app") },
  {
    path: "/app",
    element: <SessionGate />,
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
          { index: true, element: <PlaceholderPage /> },
          { path: "*", element: <NotFoundPage /> },
        ],
      },
    ],
  },
  { path: "*", element: <NotFoundPage /> },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
