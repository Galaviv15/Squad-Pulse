import { QueryClientProvider } from "@tanstack/react-query";
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { RouterProvider } from "react-router/dom";
import { createAppRouter } from "./app/router.tsx";
import { DirectionProvider } from "./components/ui/direction.tsx";
import "./i18n/i18n.ts";
import "./index.css";
import { authSession } from "./lib/api/session.ts";
import { bindSessionToQueryClient } from "./lib/auth/bindSessionToQueryClient.ts";
import { createQueryClient } from "./lib/queryClient.ts";

const queryClient = createQueryClient();
// Lives as long as the page: nothing unbinds it.
bindSessionToQueryClient(authSession, queryClient);
const router = createAppRouter();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    {/* Base UI components (menus, popovers, sliders, ...) default to LTR; index.html is RTL. */}
    <DirectionProvider direction="rtl">
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </DirectionProvider>
  </StrictMode>,
);
