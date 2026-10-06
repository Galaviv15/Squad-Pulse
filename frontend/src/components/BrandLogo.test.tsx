import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithProviders } from "@/test/render";
import { BrandLogo } from "./BrandLogo";

function renderLogo(tone?: "light" | "dark") {
  renderWithProviders({ routes: [{ path: "/", element: <BrandLogo tone={tone} /> }] });
  return screen.getByRole("img", { name: "SquadPulse" });
}

describe("BrandLogo", () => {
  it("is one image named SquadPulse, its parts hidden from assistive technology", () => {
    const logo = renderLogo();

    expect(logo).toHaveAccessibleName("SquadPulse");
    expect(screen.getAllByRole("img")).toHaveLength(1);
    for (const part of logo.children) {
      expect(part).toHaveAttribute("aria-hidden", "true");
    }
  });

  it("shows the wordmark as an LTR island, Squad then Pulse", () => {
    const logo = renderLogo();

    const wordmark = logo.querySelector("[dir='ltr']");
    expect(wordmark).toHaveTextContent(/^SquadPulse$/);
    expect([...wordmark!.children].map((part) => part.textContent)).toEqual(["Squad", "Pulse"]);
  });

  it.each([
    ["light", ["bg-sidebar", "text-brand-pulse-bright"], "text-sidebar", "text-brand-pulse"],
    [
      "dark",
      ["bg-sidebar-accent", "text-brand-pulse"],
      "text-sidebar-accent",
      "text-brand-pulse-bright",
    ],
  ] as const)("uses the %s tone's colors", (tone, markClasses, squadClass, pulseClass) => {
    const logo = renderLogo(tone);

    const [mark, wordmark] = logo.children;
    expect(mark).toHaveClass(...markClasses);
    expect(wordmark.children[0]).toHaveClass(squadClass);
    expect(wordmark.children[1]).toHaveClass(pulseClass);
  });
});
