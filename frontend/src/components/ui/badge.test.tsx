import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { renderWithProviders } from "@/test/render";
import { Badge } from "./badge";

describe("Badge", () => {
  it("shows a position code as an LTR island inside the RTL app", () => {
    renderWithProviders({ routes: [{ path: "/", element: <Badge dir="ltr">CB</Badge> }] });

    expect(screen.getByText("CB")).toHaveAttribute("dir", "ltr");
  });
});
