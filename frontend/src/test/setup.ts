import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";
// Adds jest-dom matchers (toBeInTheDocument, ...) to Vitest's expect.
import "@testing-library/jest-dom/vitest";

// Testing Library unmounts after each test by itself only when Vitest's globals are on; they
// aren't here, so do it explicitly.
afterEach(() => {
  cleanup();
});
