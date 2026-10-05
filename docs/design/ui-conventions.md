# UI conventions

The design direction approved in KAN-44 (2026-10-05). The sections up to "Implementation notes" are the approved text, unchanged.

### Decisions

* **Look:** muted deep "pitch" green on a cream / off-white base. Professional, not game-like.
* **Light mode only** in the MVP. Every color still goes through CSS variables, so dark mode later is one more set of values.
* **Font: Heebo, self-hosted** via `@fontsource` (no Google Fonts request). Weights 400/500/600/700. Numbers use `font-variant-numeric: tabular-nums` (jersey numbers, ages, stats); KAN-45 must verify the installed Heebo build supports `tnum`, not assume it.
* **Mobile:** desktop/tablet first. On a phone nothing breaks (sidebar stacks, wide tables scroll horizontally), but it isn't polished.

### Theme tokens (shadcn/ui names, hex values)

Format-neutral on purpose: KAN-45 converts them to whatever its Tailwind 3 vs 4 decision requires (HSL triplets for Tailwind 3, OKLCH / `@theme inline` for Tailwind 4).

| Token | Value |
| --- | --- |
| `--background` | `#F6F4EE` |
| `--foreground` | `#1C2420` |
| `--card` / `--popover` | `#FFFEFB` |
| `--card-foreground` / `--popover-foreground` | `#1C2420` |
| `--primary` | `#2F5D4A` |
| `--primary-foreground` | `#FAF8F2` |
| `--secondary` / `--accent` | `#E6EDE8` |
| `--secondary-foreground` / `--accent-foreground` | `#23463A` |
| `--muted` | `#EEEBE3` |
| `--muted-foreground` | `#5E655E` |
| `--border` | `#E2DDD1` |
| `--input` | `#D6D0C2` |
| `--ring` | `#2F5D4A` |
| `--destructive` | `#B4462F` (foreground `#FFFFFF`) |
| `--success` / `--success-muted` (custom, "fit") | `#2F6B45` on `#E3EFE5` |
| `--danger` / `--danger-muted` (custom, "injured", error text) | `#9A3F27` on `#F6E3DC` |
| `--sidebar` / `--sidebar-foreground` | `#1E3A2F` / `#E8E4D8` |
| `--sidebar-muted-foreground` | `#A9B5AD` |
| `--sidebar-accent` / `--sidebar-accent-foreground` (active item) | `#F2EEE3` / `#1E3A2F` |
| `--chart-1` GK / `--chart-2` DEF / `--chart-3` MID / `--chart-4` ATT | `#C2841A` / `#0E7F5C` / `#8FB83F` / `#3A6DB8` |
| `--radius` | `0.5rem` (8px; md 6px, sm 4px) |

All text pairs were checked at ≥ 4.5:1. The chart palette passed a colorblind-separation check; `--chart-1` and `--chart-3` are under 3:1 against the card, so a chart must always carry a legend with the line name and count (never color alone).

### UI conventions

* **Shell:** dark green sidebar on the start side (right in RTL): club logo + name at the top, nav items, then a "בקרוב" group shown disabled (not links): לו״ז, אימונים, משחקים, לוח טקטי. Active item = cream pill. Top bar in the content area: page title (start), user avatar + name + title and a logout button (end).
* **Type scale:** page title 22/700, section 18/600, card title 16/600, body/table 14/400, labels 13/500, badges 12/500, KPI numbers 40/700.
* **Density:** controls 40px high, filter controls 36px, table rows 52px. 4px spacing base; page padding 32px, card padding 20–24px, gap between cards 20px.
* **Cards:** `--card` background, 1px `--border`, `--radius`, no shadows. A "coming soon" placeholder is a dashed `--input` border, transparent, a muted "בקרוב" pill, no fake data, not clickable.
* **Badges:** position codes as LTR chips in English (primary filled `--secondary`, secondary outlined); medical status as pills (fit = success, injured = danger); released = muted pill, and a released player's row or card is dimmed.
* **Dashboard (KAN-51):** KPI tiles (active players, average age) + a donut of `lines` with a legend (color, line name, count; no percentages) and the total in the center; then placeholder cards for next training, next match, league table, and a wide "לו״ז שבועי" placeholder with 7 empty day columns (ראשון–שבת).
* **Squad table (KAN-49):** status as a segmented control (פעילים / משוחררים / הכל), filter bar (position, age range, medical status, foot, clear), count of shown players, primary button "הוספת שחקן". Columns: #, name with avatar, position (primary + secondary), age, height, weight, foot, medical status, row actions menu.
* **Icons:** stroke icons (lucide, which ships with shadcn/ui), 16–18px.
* **RTL:** logical properties only; LTR islands (`dir="ltr"`) for position codes, formations and `#9`-style numbers.

## Implementation notes

Written in KAN-45; not part of the approved design above.

* **Where the tokens live:** `frontend/src/index.css`. Each token is a CSS variable on `:root`, holding the hex value from the table above unchanged (no conversion), and is mapped to a Tailwind color in `@theme inline` as `--color-<token>`. A dark mode later is a second set of values for the same variables. There is deliberately no `.dark` block yet.
* **Using a token:** any Tailwind color utility followed by the token name: `bg-background`, `text-foreground`, `bg-primary text-primary-foreground`, `border-border`, `bg-success-muted text-success`, `bg-danger-muted text-danger`, `bg-sidebar text-sidebar-foreground`, `text-sidebar-muted-foreground`, `bg-sidebar-accent text-sidebar-accent-foreground`, `fill-chart-2`, `bg-destructive text-destructive-foreground`. Opacity modifiers work (`bg-primary/50` compiles to `color-mix(in oklab, var(--primary) 50%, transparent)`). Never use raw Tailwind palette colors (`emerald-*`, `neutral-*`, ...) or hex values in components.
* **Sidebar tokens:** only the five above. shadcn's own extra sidebar tokens (`sidebar-primary`, `sidebar-border`, `sidebar-ring`, ...) and `chart-5` were removed, since no generated component uses them. If the shadcn `sidebar` component is ever added, map its tokens onto these first.
* **Radius:** `--radius` is `0.5rem`. `rounded-sm` is `--radius - 4px` (4px), `rounded-md` is `--radius - 2px` (6px) and `rounded-lg` is `--radius` (8px). `rounded-xl` and above keep shadcn's multiplied scale.
* **Font:** Heebo from `@fontsource-variable/heebo` (one variable `woff2` file per subset covering all weights, so 400/500/600/700 cost no extra requests), imported in `index.css` and set as `font-sans` with a system fallback. The Hebrew subset (`unicode-range` incl. `U+0590-05FF`) is bundled into `dist/`, and nothing is loaded from Google Fonts.
* **`tnum`:** the installed Heebo build has **no** `tnum` OpenType feature (its GSUB features are `ccmp`, `frac`, `liga`, `locl`). Its default digits are already equal-width within a weight (all of `0`–`9` share one advance width), so numbers line up in columns anyway. Still use Tailwind's `tabular-nums` class on jersey numbers, ages and stats as the design says: it's harmless now and keeps working if the font changes.
* **RTL and shadcn/ui:** `frontend/components.json` has `rtl: true`, so `npx shadcn add` rewrites physical classes to logical ones (`ms-*`, `ps-*`, `start-*`, `text-start`, ...; see https://ui.shadcn.com/docs/rtl). Still check every new component under RTL: grep it for physical-direction classes, and look at it in the app. Menus, popovers, sliders and date pickers also need direction from a `DirectionProvider` (shadcn's `direction` component), which isn't added yet. The first ticket that adds such a component adds it too.
* **Generated components vs this design:** the generated `Card` uses a `ring-1 ring-foreground/10` outline and `rounded-xl`, and `Button` / `Input` are 32px high (`h-8`). The design asks for a 1px `--border`, `--radius`, and 40px controls (36px in filters). The screen tickets adjust these when they build the real screens.
