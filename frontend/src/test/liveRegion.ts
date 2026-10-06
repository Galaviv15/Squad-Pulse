/**
 * Records how `text` first enters the page: into a role="status" region that was already there
 * (announced by screen readers), or as part of a newly inserted subtree that brings its region
 * along (not reliably announced). `intoExistingRegion` stays null until the text shows up.
 */
export function watchLiveInsertion(text: string) {
  const result: { intoExistingRegion: boolean | null } = { intoExistingRegion: null };
  const observer = new MutationObserver((mutations) => {
    for (const mutation of mutations) {
      const nodes = [...mutation.addedNodes];
      const changedText = mutation.type === "characterData" ? [mutation.target] : [];
      if (
        result.intoExistingRegion === null &&
        [...nodes, ...changedText].some((node) => node.textContent?.includes(text))
      ) {
        const parent =
          mutation.target instanceof Element ? mutation.target : mutation.target.parentElement;
        result.intoExistingRegion = parent?.closest('[role="status"]') != null;
      }
    }
  });
  observer.observe(document.body, { childList: true, subtree: true, characterData: true });
  return { result, stop: () => observer.disconnect() };
}
