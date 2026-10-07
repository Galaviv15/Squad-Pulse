import { afterEach, describe, expect, it, vi } from "vitest";
import { isOpenClick } from "./openClick";

/** A row or card holding a link, a button with an icon inside, and plain text. */
function card() {
  const root = document.createElement("div");
  root.innerHTML =
    '<a href="/x">name</a><button type="button"><svg></svg></button><span>text</span>';
  document.body.append(root);
  return root;
}

afterEach(() => {
  document.body.innerHTML = "";
  vi.restoreAllMocks();
});

describe("isOpenClick", () => {
  it("is true for a click on plain content inside the row or card", () => {
    const root = card();
    expect(isOpenClick({ target: root.querySelector("span"), currentTarget: root })).toBe(true);
    expect(isOpenClick({ target: root, currentTarget: root })).toBe(true);
  });

  it("is false for a link, a button, or anything inside a button", () => {
    const root = card();
    for (const selector of ["a", "button", "svg"]) {
      expect(isOpenClick({ target: root.querySelector(selector), currentTarget: root })).toBe(
        false,
      );
    }
  });

  it("is false for a click bubbled (through a React portal) from outside it in the DOM", () => {
    const root = card();
    const popup = document.createElement("div");
    document.body.append(popup);
    expect(isOpenClick({ target: popup, currentTarget: root })).toBe(false);
  });

  it("is false for a target that isn't an element", () => {
    const root = card();
    expect(isOpenClick({ target: null, currentTarget: root })).toBe(false);
    expect(isOpenClick({ target: window, currentTarget: root })).toBe(false);
  });

  it("is false for a click that ends a text selection", () => {
    const root = card();
    vi.spyOn(window, "getSelection").mockReturnValue({ toString: () => "text" } as Selection);
    expect(isOpenClick({ target: root.querySelector("span"), currentTarget: root })).toBe(false);
  });
});
