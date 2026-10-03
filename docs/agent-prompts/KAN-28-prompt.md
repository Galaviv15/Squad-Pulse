# KAN-28 — Squad summary: average age and players per line

## Step 0 — Start from a clean, current master

```
git checkout master && git pull
```

Verify local `master` equals `origin/master` (`git rev-parse master origin/master` must print the same hash; expected to be at or after `c3a73d8`, the KAN-27 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-28-squad-summary`.

## Context

Jira KAN-28 (parent epic KAN-11 "Squad Management"), blocked by KAN-26 (Done). A read-only endpoint for the squad screen / dashboard: how many active players the club has, their average age, and how many play in each line.

Read first: `CLAUDE.md`, `docs/spec.md` sections 03, 04 and 05, and the whole `squad` package. Pay particular attention to `Position`, `PlayerService` (its two constructors and the `Clock`, `list`, `load`, `matches`), `PlayerRepository.findByClubIdAndActive`, `PlayerController` (and its Javadoc on why it is **not** `@Validated`), `PlayerResponse`. Read the tests too: `PlayerControllerTest` (the `@WebMvcTest(controllers = PlayerController.class)` setup and the permission matrix `eachEndpointAdmitsExactlyTheLevelsAtOrAboveItsMinimum`) and `PlayerApiIntegrationTest`. Also read `common/EndpointAuthorizationRuleTest` and `common/ClubScopedRepositoryMethodNamingRuleTest` (ArchUnit).

Things that already exist and must be **reused, not duplicated**:
- `PlayerRepository.findByClubIdAndActive(clubContext.requireClubId(), true)`, the club-scoped way to load the active roster. **No new repository methods, no `MongoTemplate` / aggregation queries.** The summary is computed in memory, exactly like list filtering.
- `PlayerService`'s `Clock`: production uses `Clock.systemDefaultZone()` (the same zone `@AdultAge` uses, documented in the constructor Javadoc); tests use the package-private constructor that takes a `Clock`. Do **not** create a second clock or a second service with its own clock.
- The permission hierarchy and the rule that every handler declares `@PreAuthorize` (ArchUnit).

## Agreed design (decided with Gal, implement exactly this)

### Endpoint

`GET /squad/summary`, `@PreAuthorize("hasAuthority('VIEW_ONLY')")`, always the caller's own club. No query parameters, no body.

Put it in a **new controller** `squad/SquadSummaryController` (`@RequestMapping("/squad/summary")` or equivalent). The existing `PlayerController` is mapped to `/squad/players`; don't change that mapping. Like `PlayerController`, the new controller is **not** `@Validated`. The logic goes in a new method `PlayerService.summary()`, so it shares the existing clock.

### Response

```json
{
  "playerCount": 23,
  "averageAge": 26.4,
  "lines": { "GOALKEEPERS": 3, "DEFENSE": 8, "MIDFIELD": 7, "ATTACK": 5 }
}
```

A new response record (e.g. `SquadSummaryResponse`), package-private like the other DTOs. Field names exactly as above.

- **Scope:** the club's **active** players only. Released players and other clubs' players never count. Medical status doesn't matter: an injured active player counts.
- **`playerCount`:** the number of active players.
- **`lines`:** always contains **all four** keys, in the order `GOALKEEPERS`, `DEFENSE`, `MIDFIELD`, `ATTACK`, with `0` for an empty line (also when the squad is empty). Each player counts once, by **primary position only**. The secondary position is ignored. So the four values always add up to `playerCount`. Suggested implementation: an `EnumMap<Line, Integer>` pre-filled with 0 for every `Line`. Whatever you use, verify the actual JSON key order and names with a test (see Tests).
- **`averageAge`:** the mean of each player's **exact (fractional) age** on today's date from `PlayerService`'s clock, rounded to **1 decimal, `RoundingMode.HALF_UP`**. Use `BigDecimal` for the rounding, not `Math.round` on a double.
  - **Exact age, decided:** don't use completed years (`Period.getYears()`). Averaging whole years skews the result down by about half a year. Define a player's exact age as: completed years (`Period.between(dob, today).getYears()`), **plus** the fraction of the current birthday-year elapsed. That fraction is `days(lastBirthday → today) / days(lastBirthday → nextBirthday)`, where `lastBirthday = dob.plusYears(completedYears)` and `nextBirthday = dob.plusYears(completedYears + 1)`. This way the whole-number part always equals the age used by the list filter and `@AdultAge`, and a 29 February birthday is handled by `LocalDate.plusYears` (verify what it does in a non-leap year, and test it).
  - The age computation must live in **one** helper. If you touch the existing `Period.between(...).getYears()` in `matches`, it must still behave exactly as now.
  - **A player with no `dateOfBirth`:** DOB is required by the request DTOs, but Bean Validation isn't run on save (see CLAUDE.md) and `matches()` already guards against `null`. Such a player **counts** in `playerCount` and in its line, but is **left out of the average** (the denominator is the number of players with a DOB).
  - **`null`** when no active player has a DOB, including an empty squad. Must be serialized as `"averageAge": null`, **not omitted**. Verify the actual Jackson inclusion behaviour in this project (Jackson 3 / Boot 4); don't assume.
  - A whole-number average must still serialize as a plain JSON number (e.g. `26.0` or `26`, never `"26.0"` as a string and never `2.6E+1`). Verify with a test and report which form you get.

### Line mapping (single source of truth)

- New enum `squad/Line`: `GOALKEEPERS`, `DEFENSE`, `MIDFIELD`, `ATTACK` (declared in that order).
- `Position` gets a `line` attribute through its constructor, plus a getter (`line()` or `getLine()`, match the project's style):
  - `GK` → `GOALKEEPERS`
  - `CB`, `RB`, `LB` → `DEFENSE`
  - `DM`, `CM`, `AM` → `MIDFIELD`
  - `RW`, `LW`, `ST` → `ATTACK`
- Keep the existing per-constant Javadoc and the class Javadoc about English codes. Adding a constructor must not change how `Position` is (de)serialized or stored: it's still the plain enum name in JSON and in Mongo. Verify this; don't assume.
- **No other place in the code may hard-code which position belongs to which line.**

### General

- Update Javadocs: `PlayerService` class Javadoc (it now also computes the summary), and the new controller's Javadoc (permission, active players only, the age definition, `null` average).
- `README` and `CLAUDE.md` (rule 7): add the endpoint to the README "Squad API" table, and add it to the CLAUDE.md status line next to the other squad endpoints. Re-check both against the code before committing.

## Verify against what's installed

Don't rely on general knowledge for framework behaviour. Check the versions in `backend/pom.xml` / the resolved dependencies (notes say: Spring Boot 4.1.1, Spring Framework 7.0.9, Spring Data MongoDB 5.1.1, driver 5.8.1, Jackson 3.1.5 under `tools.jackson`, Hibernate Validator 9.1.3, mongo:7). Verify these, with a test or in the installed sources, not from memory:
1. `averageAge: null` is present in the JSON (not dropped), and how a `BigDecimal` such as `26.0` is written.
2. The JSON key order and names of the `lines` map. An `EnumMap` key is written by `name()` and in declaration order, unless a key serializer or naming strategy says otherwise.
3. Adding a constructor/field to `Position` doesn't change its JSON or BSON representation (existing `PlayerRepositoryIntegrationTest` / API tests should still pass unchanged; say which ones prove it).
4. `LocalDate.plusYears` on a 29 Feb DOB in a non-leap year (what date it returns), and the resulting exact age.

If anything differs from this prompt, stop and report rather than improvising.

## Tests (required)

Follow the existing style. For the WebMvc slice, add a **new** `SquadSummaryControllerTest` with the same setup as `PlayerControllerTest` (`AuthWebMvcTestConfig`, `TestAccessTokens`, mocked `PlayerService`), or widen `PlayerControllerTest`'s `controllers` list. Pick one, and say which and why. End-to-end tests go in `PlayerApiIntegrationTest` (or a new integration test class, same infrastructure, real Mongo).

1. **Line mapping, exhaustive and pinned:** a test with an explicit expected table of all 10 positions → line, asserting `Position.values()` maps exactly to it (same size, every entry). The constructor already forces a line for every new constant, so this test's job is to pin *which* line, and to fail when a position is added without updating the table.
2. **Permission matrix:** `VIEW_ONLY`, `EDIT_PARTIAL`, `EDIT_FULL`, `ADMIN` → 200; no token → 401. (Nothing is below `VIEW_ONLY`, so expect no 403 case. Say so if you find otherwise.) Make sure the ArchUnit authorization rule covers the new handler.
3. **Exact age, unit-level with a fixed `Clock`:** a birthday today (exact whole number); the day before a birthday; a 29 Feb DOB checked on 28 Feb and 1 Mar of a non-leap year; the whole-number part equals `Period.getYears()` in all cases.
4. **Average and rounding:** a hand-computed example whose correct answer differs between "exact" and "completed years" (proves the exact method is used); a case that lands on a `.x5` boundary (proves `HALF_UP`); a single player.
5. **Null/empty:** empty squad → `playerCount 0`, `averageAge null` (present in JSON), all four lines `0`; only released players → same; a player with no DOB (insert it raw, since the API can't create one) counts in `playerCount` and its line but not in the average; all players without DOB → `averageAge null`.
6. **Scope (integration, real Mongo):** two clubs plus released players in the caller's club. Only the caller's **active** players count, in both the count and the average. An injured active player counts. A player whose **secondary** position is in another line counts only in its primary line.
7. **JSON shape:** assert the exact JSON body for one realistic squad (field names, the four `lines` keys in order, number format).

Run the full build with JAVA_HOME = Temurin 21 (Spotless crashes on JDK 25): `./mvnw verify` (or the project's usual command). Everything green, Spotless clean. Report the total test count (it was 570 after KAN-27).

## Docs

- **README** "Squad API": add `GET /squad/summary` (`VIEW_ONLY`) with the response shape, "active players only", the line grouping, the exact-age definition, rounding, and when `averageAge` is `null`. Update the status line if needed.
- **CLAUDE.md:** add the endpoint to the status line (rule 7).
- **`docs/spec.md`: do NOT edit it** (CLAUDE.md). Instead list in your summary, precisely, every place in the spec that needs updating (expected: the status line, section 05 (the line grouping, probably under "Positions", and the summary endpoint under "Squad API behaviour"), the roadmap phase table) and the exact facts to write there.

## Commits

Small, focused commits in the existing style, e.g. `feat(squad): ... (KAN-28)`, `test(squad): ... (KAN-28)`, `docs(readme): ... (KAN-28)`. **Do not push and do not open a PR.** That comes in a later prompt, after review.

## Summary to return

1. Files changed/added, one line each.
2. The final API (endpoint, permission, exact response JSON for an example squad) as actually implemented.
3. Evidence for each item in "Verify against what's installed" (what you checked, where, what you found).
4. Test list with counts, and the full build result (command + outcome + total test count).
5. Any deviation from this prompt, and why.
6. The spec update list (see Docs).
7. Open ends / risks you noticed.
8. Print the full final contents of `Position.java`, `Line.java`, `SquadSummaryController.java`, the response record, and the `summary()` method plus the age helper from `PlayerService`, so they can be reviewed directly.
