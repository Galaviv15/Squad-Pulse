import { Outlet } from "react-router";
import { Sidebar } from "@/components/shell/Sidebar";
import { TopBar } from "@/components/shell/TopBar";
import { useDocumentTitle, usePageTitle } from "./pageTitle";

/**
 * The layout of every protected page: the sidebar first (the start side, right in RTL), then the
 * content column with the top bar and the page in <main>. It sits below RequireAuth, which renders
 * it only once /me has loaded. The page title comes from the matched route's handle (titleKey):
 * pages render no <h1> of their own.
 */
export function AppShell() {
  const pageTitle = usePageTitle();
  useDocumentTitle(pageTitle);

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground md:flex-row">
      <Sidebar />
      {/* min-w-0: a wide table scrolls inside the column instead of widening the page. */}
      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar title={pageTitle} />
        <main className="w-full max-w-[1200px] px-4 py-7 md:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
