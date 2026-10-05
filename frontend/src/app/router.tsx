import { createBrowserRouter, redirect, type RouteObject } from "react-router";
import { NotFoundPage } from "@/pages/NotFoundPage";
import { PlaceholderPage } from "@/pages/PlaceholderPage";

/**
 * The app's route table. Every SPA route lives under /app: the backend owns /auth, /squad, /clubs
 * and /users (proxied in dev), so a browser reload on an SPA URL must never use one of those.
 * The app shell (KAN-48) becomes a parent route of /app here.
 */
export const routes: RouteObject[] = [
  { path: "/", loader: () => redirect("/app") },
  { path: "/app", element: <PlaceholderPage /> },
  { path: "*", element: <NotFoundPage /> },
];

export function createAppRouter() {
  return createBrowserRouter(routes);
}
