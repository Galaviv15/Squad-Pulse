/** The parts of a click that decide whether it opens a player (a React MouseEvent has them). */
export interface OpenClickEvent {
  target: EventTarget | null;
  currentTarget: Element;
}

/**
 * Whether a click on a player's row (the table) or card (the cards view) is an "open" click, a
 * mouse convenience next to the name link (the keyboard path):
 *
 * - React bubbles a click inside a portal (the actions menu's popup) up the component tree to the
 *   row or card, though the popup isn't inside it in the DOM: only a click inside it counts. (The
 *   dialogs are rendered by the page, outside the list, so their clicks never get here.)
 * - A link or button (the name, the menu's trigger) acts by itself.
 * - A click that ends a text selection isn't an "open" click.
 *
 * Anything portaled into a clickable row or card later is covered by the first rule.
 */
export function isOpenClick({ target, currentTarget }: OpenClickEvent): boolean {
  if (!(target instanceof Element) || !currentTarget.contains(target)) {
    return false;
  }
  if (target.closest("a, button")) {
    return false;
  }
  return (window.getSelection()?.toString() ?? "") === "";
}
