/**
 * The segmented-control look shared by the squad toolbar's radio groups (StatusControl,
 * ViewToggle): a 36px `bg-muted` row with 3px padding, the selected option `bg-card` with a 1px
 * `border-border`. Each control adds its options' own size and type.
 */
export const SEGMENTED_GROUP_CLASSES =
  "inline-flex h-9 items-stretch gap-0.5 rounded-lg bg-muted p-[3px]";

export const SEGMENTED_OPTION_CLASSES =
  "flex cursor-pointer items-center rounded-md border border-transparent text-muted-foreground transition-colors outline-none select-none hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 data-checked:border-border data-checked:bg-card data-checked:text-foreground";
