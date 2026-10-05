import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useDirection } from "@/components/ui/direction";
import { renderWithProviders } from "./render";

function ShowDirection() {
  return <p>{useDirection()}</p>;
}

describe("renderWithProviders", () => {
  it("renders in RTL for Base UI components, as main.tsx does", async () => {
    renderWithProviders({
      initialEntries: ["/"],
      routes: [{ path: "/", element: <ShowDirection /> }],
    });

    expect(await screen.findByText("rtl")).toBeInTheDocument();
  });
});
