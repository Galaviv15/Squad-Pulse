/**
 * The scrollTop that centres a row in a scroll container whose top `headerHeight` pixels are
 * covered by a sticky header: `rowTop` is the row's offset from the top of the scrolled content
 * (header included). Never negative; the browser clamps the other end itself.
 */
export function centredScrollTop({
  rowTop,
  rowHeight,
  containerHeight,
  headerHeight,
}: {
  rowTop: number;
  rowHeight: number;
  containerHeight: number;
  headerHeight: number;
}): number {
  const visible = containerHeight - headerHeight;
  return Math.max(0, Math.round(rowTop - headerHeight - (visible - rowHeight) / 2));
}

/**
 * Scrolls `container` (only it) so that `row` is centred below its sticky `header`. Sets the
 * container's scrollTop: never row.scrollIntoView(), which also scrolls every scrollable ancestor,
 * the page included, and would jump the dashboard down on load.
 */
export function centreRowInContainer(
  container: HTMLElement,
  row: HTMLElement,
  header: HTMLElement | null,
) {
  const containerRect = container.getBoundingClientRect();
  const rowRect = row.getBoundingClientRect();
  container.scrollTop = centredScrollTop({
    rowTop: rowRect.top - containerRect.top + container.scrollTop,
    rowHeight: rowRect.height,
    containerHeight: container.clientHeight,
    headerHeight: header?.getBoundingClientRect().height ?? 0,
  });
}
