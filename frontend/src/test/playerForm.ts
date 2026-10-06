import { fireEvent, screen, waitFor } from "@testing-library/react";
import { expect } from "vitest";
import he from "@/i18n/locales/he.json";

/**
 * Driving the player form in tests. Fields are found by their visible label; the selects are Base
 * UI comboboxes named by it (aria-labelledby).
 */

/** The input labelled `label` (its text without the required mark "*", which is aria-hidden). */
export function input(label: string): HTMLInputElement {
  const element = screen
    .getAllByText(
      (_, node) => node?.tagName === "LABEL" && node.textContent?.replace(/\*$/, "") === label,
    )
    .map((node) => document.getElementById((node as HTMLLabelElement).htmlFor))[0];
  if (!(element instanceof HTMLInputElement)) {
    throw new Error(`No input labelled ${label}`);
  }
  return element;
}
export const combobox = (label: string) => screen.getByRole("combobox", { name: label });

/** Types into a text, number or date field (jsdom keeps a date only if it's a valid yyyy-MM-dd). */
export function type(label: string, value: string) {
  fireEvent.change(input(label), { target: { value } });
}

/** Opens a select and chooses an option as a mouse does (Base UI: pointerdown + click). */
export async function choose(label: string, option: string) {
  fireEvent.click(combobox(label));
  const item = await screen.findByRole("option", { name: option });
  fireEvent.pointerDown(item, { pointerType: "mouse" });
  fireEvent.click(item);
  await waitFor(() => expect(screen.queryByRole("listbox")).toBeNull());
}

export const fields = he.squad.fields;

/** The error shown under a field (its aria-describedby), or null. */
export function errorOf(element: HTMLElement): string | null {
  const id = element.getAttribute("aria-describedby");
  return id === null ? null : (document.getElementById(id)?.textContent ?? null);
}

export const submitButton = (name: string) => screen.getByRole("button", { name });
