# UI conventions

The design direction approved in KAN-44 (2026-10-05). The sections up to "Implementation notes" are the approved text, unchanged apart from the logo, approved in KAN-47 (its "Logo" decision and two tokens).

### Decisions

* **Look:** muted deep "pitch" green on a cream / off-white base. Professional, not game-like.
* **Light mode only** in the MVP. Every color still goes through CSS variables, so dark mode later is one more set of values.
* **Font: Heebo, self-hosted** via `@fontsource` (no Google Fonts request). Weights 400/500/600/700. Numbers use `font-variant-numeric: tabular-nums` (jersey numbers, ages, stats); KAN-45 must verify the installed Heebo build supports `tnum`, not assume it.
* **Logo (KAN-47):** a 40×40 `rounded-lg` mark with a stroke "pulse" icon, beside the wordmark "SquadPulse" (22/700, `letter-spacing: -0.01em`, an LTR island) in two colors. On a light background: mark `--sidebar` with the icon in `--brand-pulse-bright`, "Squad" in `--sidebar`, "Pulse" in `--brand-pulse`. On a dark background (the sidebar): mark `--sidebar-accent` with the icon in `--brand-pulse`, "Squad" in `--sidebar-accent`, "Pulse" in `--brand-pulse-bright`. `--brand-pulse` is 3.7:1 on `--background` (and `--brand-pulse-bright` 5.4:1 on `--sidebar`), so it's for the large wordmark only, never body text. `--brand-pulse-bright` has `--chart-3`'s value but is its own token, so brand and chart colors can change independently.
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
| `--brand-pulse` / `--brand-pulse-bright` (logo, KAN-47; large wordmark only) | `#5E8A2E` / `#8FB83F` |
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
* **RTL and shadcn/ui:** `frontend/components.json` has `rtl: true`, so `npx shadcn add` rewrites physical classes to logical ones (`ms-*`, `ps-*`, `start-*`, `text-start`, ...; see https://ui.shadcn.com/docs/rtl). Still check every new component under RTL: grep it for physical-direction classes, and look at it in the app. Base UI components (menus, popovers, sliders, ...) default to LTR, so the app and the test render helper wrap everything in `<DirectionProvider direction="rtl">` from `@/components/ui/direction` (shadcn's `direction` component; https://ui.shadcn.com/docs/rtl/vite, https://ui.shadcn.com/docs/components/base/direction).
* **Base components** (`frontend/src/components/ui`, adjusted to this design in KAN-45; use these variants and sizes rather than restyling per screen):
  * `Card`: `--card` background, 1px `--border`, `rounded-lg` (`--radius`), no ring or shadow, 20px padding (`--card-spacing`). `CardTitle` is 16/600. No size variants.
  * `Button`: sizes `default` (40px), `sm` (36px, filter bars), `icon` (40×40) and `icon-sm` (36×36). Variants: `default` (primary), `outline`, `secondary`, `ghost`, `destructive`, `link`. The generated `xs`, `lg`, `icon-xs` and `icon-lg` sizes were removed.
  * `Input`: `size="default"` (40px) or `size="sm"` (36px, filter bars). This `size` prop replaces the native `<input size>` attribute.
  * `Label`: 13/500.
  * `Badge`: a 12/500 `rounded-full` pill. Variants: `default` (filled `--secondary`, a primary position chip), `outline` (a secondary position chip), `success` ("fit": `--success` on `--success-muted`), `danger` ("injured": `--danger` on `--danger-muted`), `muted` ("released"). Position codes go in an LTR island: `<Badge dir="ltr">CB</Badge>`. The generated `secondary`, `destructive`, `ghost` and `link` variants were removed: `default` and `danger` cover them.
* **Logo** (KAN-47): `frontend/src/components/BrandLogo.tsx`, `<BrandLogo />` on light backgrounds and `<BrandLogo tone="dark" />` on the sidebar. It's one `role="img"` named "SquadPulse" (its mark and the two wordmark halves are `aria-hidden`), so a screen reader says the name once, not "Squad" and "Pulse". The colors are the `brand-pulse` / `brand-pulse-bright` utilities (`text-brand-pulse`, ...), only for the logo.
* **Auth screens** (KAN-47): `frontend/src/components/auth` holds their shared pieces: `AuthLayout` (logo, content, footer, centered), `AuthCard` (420px card, 32/28px padding, `<h1>` 22/700 and subtitle), `AuthField` (label, 40px input, hint or error linked by `aria-describedby`, `--danger` border when invalid), `FormAlert` (`role="alert"`, `--danger` on `--danger-muted`) / `FormNotice` (`role="status"`, `--secondary`), and the full-page `LoadingScreen` / `ServerErrorScreen`.
