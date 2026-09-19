import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import App from "./App";
import he from "./i18n/locales/he.json";
import "./i18n/i18n";

describe("App", () => {
  it("renders the app name and Hebrew welcome text through i18n", () => {
    render(<App />);

    expect(screen.getByRole("heading", { name: he.app.name })).toBeInTheDocument();
    expect(screen.getByText(he.app.welcome)).toBeInTheDocument();
  });
});
