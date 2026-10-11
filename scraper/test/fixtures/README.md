# Parser test fixtures (KAN-64)

Trimmed samples of football.org.il responses, captured on **2026-10-11** (Israel time) for the parser tests KAN-67 will write. Only the part a parser needs is kept; scripts, styles, images, logos, navigation, ads and tracking were removed. See `../../docs/KAN-64-findings.md` for what each field means and its quirks.

| File | Source | Trimmed |
|---|---|---|
| `team-games-7617-s28.html` | `GET https://www.football.org.il/team-details/team-games/?team_id=7617&season_id=28` (server-rendered HTML, UTF-8) | Kept only `<section class="table_data_zone games_table container">` (title, header row and all 34 game rows, unchanged), wrapped in a minimal `<html lang="he" dir="rtl">` document. Removed the print button (inline SVG) and inline `style` widths. Contains 6 played games with a result and 28 unscheduled games (`game_id=-1`, time `00:00`, no venue, no result). |
| `league-62-s28-table-round6.xml` | `GET https://www.football.org.il/Components.asmx/LeagueTable?league_id=62&season_id=28&box=20&round_id=6&componentTitle=טבלת ליגה` (`text/xml`, an ASMX `ResponseData` envelope whose `HtmlData` is escaped HTML) | Kept the envelope as received. Inside `HtmlData`, removed only the loading animation (`<aside class='loader'>…</aside>`). All 18 standings rows unchanged. |
| `cloudflare-block-403.html` | The `403` page Cloudflare returned instead of the team-games page to `curl` and Node `fetch` | Reduced to the title and the two headlines a block detector needs. Removed the client IP address, the Ray ID (replaced with zeros), scripts and styles. |

Not captured: the league page itself (its standings, games and statistics sections are empty placeholders in the HTML) and the per-player statistics (`Components.asmx/LeagueStatistics`, out of scope until KAN-70).
