# KAN-77: Create a squad player from an unlinked scraped player, and link it in the same step

**Credit budget is tight.** Read only the files this prompt names, plus what you need to follow a call you're changing. Don't explore the repo broadly. Keep the summary factual and short. If you run out mid-task, stop at a clean commit and report where you stopped.

**Run every frontend command on Node 22** (what CI uses; on Node 24 some existing tests fail, that's KAN-78). Report `node --version` and `java -version`.

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master` (`git rev-parse master origin/master` prints the same hash, at or after `1ea9479`, the KAN-74 merge, PR #56). If it doesn't, stop and report. Then create the branch `feature/KAN-77-create-player-from-stats`.

Take the backend and frontend test counts on master **before your first change** (`./mvnw verify` from `backend/`, `npm test` from `frontend/`).

## Context

Jira KAN-77, epic KAN-14 ("Match & Scraping Integration"). Built on KAN-72 (backend: scraped player stats, opaque row ids, links, suggestions) and KAN-74 (frontend: the `/app/stats` screen, linking, the picker). Both are merged.

**The problem.** A new club has an empty squad. After its first refresh, all its scraped players (34 in the real Sderot data) are unlinked, and there is nothing to link them to or suggest. Today the user would have to create each player in the squad screen, then come back and link them one by one.

**This ticket.** From an unlinked scraped row, the user opens the normal create-player form with the name pre-filled, completes it, and saves. The `Player` is created **and** linked to that row in one request, atomically. This is **full-stack**: backend and frontend. No scraper change.

### What already exists (read the code, not only this list; see "Verify against reality")

- **`POST /squad/players`** (`EDIT_FULL`), `squad.PlayerController#create` → `PlayerService#create(CreatePlayerRequest)`:
  - `playerRepository.insert` (club-scoped: the repository stamps `clubId` from `ClubContext`);
  - a duplicate key on the jersey-number index → `JerseyNumberTakenException` (`409 JERSEY_NUMBER_TAKEN`), through `translateDuplicateKey`;
  - `201`, body `PlayerResponse` (`hasPhoto: false`), `Location` `/squad/players/{id}`.
  - `PlayerService`, `CreatePlayerRequest` and `PlayerResponse` are all **package-private** in `squad`.
- **`PUT /matches/player-stats/{id}/link`** (`EDIT_FULL`), `match.PlayerStatsLinkService#link`:
  - `readService.storedRow(id)` → `404` if the row isn't in the club's current snapshot (or the club isn't mapped / has no snapshot);
  - it **replaces** a scraped row's existing link. It does not refuse it;
  - its lost-race handling **re-reads** after a `DuplicateKeyException`.
- **`match` depends on `squad`, never the other way round.** `squad` sees links only through its own `squad.PlayerStatsLinks` interface, implemented in `match` (see `match/package-info.java`). Keep it so.
- **`player_stats_links`** has two unique indexes: `(clubId, sourcePlayerId)` and `(clubId, playerId)`. A link whose `Player` no longer exists counts as **no link** everywhere.
- **Multi-document transactions are available:** `common.MongoTransactionConfig` defines a `MongoTransactionManager`. `auth.ClubBootstrapService` uses a `TransactionTemplate` (club + first user, both or neither). Local and test MongoDB run as a replica set (`mongo:7`, Testcontainers `withReplicaSet()`).
- **Frontend:**
  - `/app/squad/new` (`pages/squad/NewPlayerPage.tsx`) renders `components/squad/PlayerForm.tsx` in `mode="create"` with `EMPTY_PLAYER_FORM`, `save` → `useCreatePlayer` (`lib/squad/players.ts`).
  - `PlayerForm`'s `save` must resolve with a `Player`.
  - `PlayerForm#showSaveError` maps a create `404` to the generic `"failed"`, and an unknown `409` code to `"invalid"`.
  - The stats screen: `pages/stats/StatsPage.tsx`, `components/stats/SquadPlayerCell.tsx` (with `UnlinkedActions`, left ready for this ticket), `components/stats/LinkPickerDialog.tsx`, `components/stats/useRowWrites.ts`, `lib/matches/playerStats.ts`, `lib/matches/playerStatsErrorCodes.ts`, `lib/matches/statsFilter.ts`.
  - `StatsPage` returns focus to a row through its header cell (`focusRow`).

## Decisions (agreed with Gal; not up for change in this ticket)

### 1. The endpoint

**`POST /matches/player-stats/{id}/player`**, `EDIT_FULL`, in `match` (the module that owns the row and the link).

- **Body:** exactly the `squad` `CreatePlayerRequest`, the **same type**, validated with `@Valid`. The validation must not be able to drift from `POST /squad/players`. Don't copy the record.
- **Answer:** `201`, body = the created player as `POST /squad/players` returns it (`PlayerResponse`, `hasPhoto: false`), `Location` = `/squad/players/{newId}`.
  - Build the `Location` explicitly. `fromCurrentRequest().path("/{id}")` would give `/matches/player-stats/{row}/player/{id}`, which is wrong. Test the header.
  - **Not** the stats row: the frontend form needs a `Player`, and the stats list is refetched anyway (KAN-74's rule).
- **Errors:**

  | Case | Answer | Written |
  |---|---|---|
  | Invalid body | `400`, exactly as `POST /squad/players` | nothing |
  | Below `EDIT_FULL` | `403` | nothing |
  | Row not in the club's current snapshot (or another club's row, or club not mapped / no snapshot) | `404` | nothing |
  | Row already linked to an **existing** `Player` | `409` with a **new** code, `STATS_ROW_ALREADY_LINKED` (name it so unless an existing name clashes; say what you chose) | nothing |
  | Jersey number taken | `409 JERSEY_NUMBER_TAKEN`, same as create | **nothing**: no player, no link |
  | Lost a race (see 3) | `409` (see 3) | nothing |

- **A dangling link** (to a deleted `Player`) counts as none. The new player's link replaces it.
- A `PLAYER_ALREADY_LINKED` is impossible here (a brand-new `Player` id). Don't add a branch for it; say so in a comment.
- **No football.org.il id** in any response or error (spec §09).

### 2. Module boundary

`squad` exposes the **narrowest public entry point** that lets `match` create a player through the **same** code path as `PlayerService#create`: the same field mapping, the same `insert`, the same jersey-number translation. For example, a small public interface in `squad`, implemented by `PlayerService`, taking `CreatePlayerRequest` and returning the created `Player`. `CreatePlayerRequest` and `PlayerResponse` become public only as far as needed.

- **No** new dependency from `squad` to `match`.
- **No** second copy of the field mapping.
- The existing ArchUnit rules (`common/ClubScopedRepositoryRules`, `ClubScopedRepositoryMethodNamingRuleTest`, `CrossClubLookupConfinementTest`, `PublicEndpointsConsistencyTest`, …) still pass unchanged.
- Say exactly what you made public, and why nothing less would do.

### 3. Atomicity: one MongoDB transaction, never a compensating delete

The order is fixed:

1. **`@Valid` body** (before the service is called).
2. **Outside the transaction:**
   - load the row (`storedRow(id)` → `404`);
   - check the row's link: linked to an **existing** `Player` → `409 STATS_ROW_ALREADY_LINKED`.
3. **Inside one `TransactionTemplate`** (on the existing `MongoTransactionManager`, like `ClubBootstrapService`):
   - create the `Player` through the `squad` entry point;
   - create the row's link, or replace a dangling one, with `linkedAt` from the injected `Clock`.
4. **After commit:** return the player.

**Failures inside the transaction.** The transaction is aborted, so the player is **not** created. Then map the error:

- **Jersey-number duplicate** → `JerseyNumberTakenException`, unchanged.
- **Duplicate key on `(clubId, sourcePlayerId)`** (a concurrent link of this row won) → after the abort, **outside** the transaction, re-read the row's link once:
  - linked to an existing player → `STATS_ROW_ALREADY_LINKED`;
  - otherwise → `409 CONCURRENT_MODIFICATION` (the existing `GlobalExceptionHandler` mapping).
  - **Do not reuse `PlayerStatsLinkService#afterLostRace`.** It re-reads inside the failed write's context. Re-reading inside an aborted transaction fails.
- **A MongoDB write conflict** (e.g. two requests replacing the same dangling link document) → `409 CONCURRENT_MODIFICATION`, never a `500`.
  - Find out **how the installed Spring Data MongoDB translates it**, and map it explicitly if needed.
  - **No automatic retry**: a retry would find the row linked and answer `409` anyway, and an automatic retry of a user's create is the kind of thing that creates two players.
- **No `DuplicateKeyException` or write-conflict exception may escape as a `500`.**

**Club isolation:**
- `clubId` comes only from `ClubContext`.
- The `Player` insert and the link write go through the club-scoped repositories, so the stamping and the club checks still apply inside the transaction. **Prove it with a test.**
- No new `@NotClubScoped`, no `MongoTemplate` query, no bypass.

### 4. Frontend: entry points (`EDIT_FULL` and up only)

**a. In the cell's "unlinked, no suggestion" state** (`SquadPlayerCell`, inside `UnlinkedActions`), next to "קישור לשחקן…":
- a second action **"יצירת שחקן בסגל"** (a plus / user-plus icon from the installed `lucide-react`; verify the export name);
- it's a **link** (navigation), styled as an outline small button, not a `<button>` with `onClick` + `navigate`;
- its accessible name includes the scraped name (e.g. "יצירת שחקן בסגל עבור {name}"), so 33 of them can be told apart.

**b. In `LinkPickerDialog`, for an unlinked row** (with or without a suggestion):
- below the list, a link **"＋ יצירת שחקן חדש בסגל"** to the same route;
- in the **empty-squad** state, replace "אין עדיין שחקנים בסגל." with "אין עדיין שחקנים בסגל. אפשר ליצור שחקן חדש מהשורה הזו." plus the same link;
- **not** shown when the picker was opened through "שינוי קישור" on a linked row (the endpoint would refuse it);
- following the link closes the dialog with no focus fight.

**Below `EDIT_FULL`:** none of this, and no request for the new route's data.

The suggestion state's buttons ("אישור", "אחר…") are unchanged.

### 5. Frontend: the form route

**Route:** `/app/stats/:id/new-player`.
- Lazy, like `/app/stats` (function form, static handle).
- Title key `stats.createPlayer.title` = "הוספת שחקן לסגל".
- Wrapped in the existing `RequireEditFull`.
- `:id` is the opaque row id.

**The page reads the stats** through the existing `usePlayerStats` (the same query and cache), finds the row by id, and shows:

- **Loading / read error:** the existing loading line / `QueryContent` error with retry.
- **`NOT_MAPPED`, `NO_DATA`, or no such row:** "השחקן הזה כבר לא מופיע בנתוני האתר. ייתכן שהנתונים רועננו." with a link back to the stats screen. No form.
- **Row linked to an existing player:** "השחקן מהאתר כבר מקושר ל־{fullName}." with a link to that player's card and a link back. No form.
- **Otherwise, the form:**
  - **Above it, a context block:**
    - "מהאתר: {row.name}" (the scraped name, read-only);
    - a hint: "באתר שם המשפחה מופיע קודם. בדקו את סדר השם לפני השמירה.";
  - **`PlayerForm` in `mode="create"`**, initial values `{ ...EMPTY_PLAYER_FORM, fullName: row.name }`:
    - **every** field the normal create form has, with the same required / optional rules: name, primary position (required), secondary position, jersey number, date of birth (required), height, weight, preferred foot, medical status (default FIT);
    - **no reduced form**, and no field added or removed;
    - the name is fully editable;
    - mount the form once: a background refetch of the stats must **not** remount it or reset what the user typed (`initialValues` changes are already ignored; don't key it on data that changes).

**Saving** sends the new endpoint through a new mutation (in `lib/matches/playerStats.ts` or beside it):
- **On success:**
  - the same squad cache handling as `useCreatePlayer` (`onPlayerWritten`);
  - **plus** invalidate `PLAYER_STATS_QUERY_KEY`.
- **On `404` / `409 STATS_ROW_ALREADY_LINKED` / `409 CONCURRENT_MODIFICATION`:** invalidate `PLAYER_STATS_QUERY_KEY` too.

**Errors in the form** (plain Hebrew, never the English message):
- `400`, `403`, `409 JERSEY_NUMBER_TAKEN` (on the jersey field), network / `5xx`: **exactly** as on `/app/squad/new` today.
- `404` → an alert: "השחקן הזה כבר לא מופיע בנתוני האתר, ולא נוצר שחקן. חזרו למסך הסטטיסטיקה." with a link back.
- `409 STATS_ROW_ALREADY_LINKED` → "השחקן מהאתר קושר בינתיים לשחקן אחר, ולא נוצר שחקן." with a link back.
- `409 CONCURRENT_MODIFICATION` → "מישהו אחר עדכן את הקישור בדיוק עכשיו, ולא נוצר שחקן. נסו שוב." (the form stays, the values are kept).

**How:** add a **narrow optional** extension to `PlayerForm` for host-specific save errors (e.g. a prop that maps an `ApiError` to an extra alert, or extra alert kinds). The existing hosts (`NewPlayerPage`, `EditPlayerPage`) don't pass it and behave **exactly** as before: their existing tests pass untouched. Say what you chose.

**After a successful save:**
- `navigate` back to the stats screen with `replace: true` (Back doesn't return to the form);
- **keep the filter** the user came from (`?filter=unlinked` or none);
- move focus to that row's header cell (the existing `focusRow` mechanism; pass the row id through navigation state);
- announce "{fullName} נוסף לסגל וקושר." in the screen's existing `role="status"` region.

**"ביטול"** returns the same way, focusing the row, with no announcement.

**The return path:**
- Carry the filter **only** as the known filter value (`statsFilter.ts`), rebuilt into `/app/stats` on the way back.
- **Never** accept a free return URL from the query string or state (no open redirect).

### 6. Error codes

- `STATS_ROW_ALREADY_LINKED`:
  - backend: a `ConflictException` subclass beside `PlayerAlreadyLinkedException`, with its `CODE` constant;
  - frontend: in `lib/matches/playerStatsErrorCodes.ts`, with its backend class in the comment.
- Compare `ApiError.code`, never the message.

## Out of scope

- Bulk or automatic import of scraped players.
- Any change to `PUT /matches/player-stats/{id}/link` (it keeps replacing).
- Showing link state on the player card or the squad table.
- A photo upload in this flow.
- Any scraper change. The ingest still never writes `Player`.
- Reordering the name automatically.
- Women's teams.

## Verify against reality, not memory

Check these against what's actually installed (`backend/pom.xml` → Spring Boot 4.1.1 and the Spring Data MongoDB / MongoDB driver versions it brings; `frontend/package.json` / `package-lock.json`), not general knowledge. For each, say in the summary **how** you checked it (a test, a source / Javadoc in the jar, a real response):

**Backend**

- **Spring Data MongoDB (installed):**
  - a club-scoped `MongoRepository` `insert` / `save` inside `TransactionTemplate` + `MongoTransactionManager` **joins** the transaction (proved by a rollback test, not assumed);
  - what a duplicate key **inside** a transaction surfaces as (`DuplicateKeyException`?), and that the transaction is then aborted;
  - what a write conflict (`WriteConflict` / `TransientTransactionError` label) is translated to. Map it so it's a `409 CONCURRENT_MODIFICATION`.
- **MongoDB 7:** whether writing to `player_stats_links` / `players` inside a transaction needs the collections to exist first (implicit creation in transactions), and how the tests and a fresh database cover it.
- **The raw `201` answer and headers** (`Location`) of the new endpoint, and the raw `409` bodies (`STATS_ROW_ALREADY_LINKED`, `JERSEY_NUMBER_TAKEN`, `CONCURRENT_MODIFICATION`), from an integration test's output.

**Frontend**

- **`lucide-react` (installed):** the plus / user-plus icon's export name.
- **React Router (installed 8.x):**
  - the lazy route function form;
  - `useParams`;
  - navigation `state` surviving `replace: true`;
  - how `StatsPage` can read it once (and not re-focus on later renders).
- **TanStack Query (installed):** the mutation callbacks' signatures; that invalidating `PLAYER_STATS_QUERY_KEY` while the stats screen is not mounted refetches when it mounts.

## Read first

`CLAUDE.md` (standing rules, especially 2, 3, 6 and 7), `docs/design/ui-conventions.md`, then only:

- **Backend:**
  - `match/PlayerStatsLinkService.java`, `match/PlayerStatsController.java`, `match/PlayerStatsReadService.java`, `match/PlayerStatsLink.java`, `match/PlayerStatsLinkRepository.java`, `match/PlayerAlreadyLinkedException.java`, `match/package-info.java`.
  - `squad/PlayerService.java` (create and `translateDuplicateKey`), `squad/PlayerController.java` (create), `squad/CreatePlayerRequest.java`, `squad/PlayerResponse.java`, `squad/PlayerStatsLinks.java`, `squad/JerseyNumberTakenException.java`.
  - `common/MongoTransactionConfig.java`, `common/GlobalExceptionHandler.java` (the concurrent-modification mapping), `common/ConflictException.java`.
  - `auth/ClubBootstrapService.java` (the `TransactionTemplate` pattern only).
- **Backend tests as a model:**
  - `scrapingintegration/PlayerStatsApiIntegrationTest.java`;
  - `match/PlayerStatsLinkServiceTest.java`;
  - `match/SnapshotWriteConflictIntegrationTest.java` (real concurrency);
  - `squad/PlayerApiIntegrationTest.java` (create).
- **Frontend:**
  - Routing: `app/router.tsx`.
  - Stats: `pages/stats/StatsPage.tsx`, `components/stats/SquadPlayerCell.tsx`, `components/stats/LinkPickerDialog.tsx`, `components/stats/useRowWrites.ts`, `lib/matches/playerStats.ts`, `lib/matches/playerStatsErrorCodes.ts`, `lib/matches/statsFilter.ts`.
  - Squad: `pages/squad/NewPlayerPage.tsx`, `pages/squad/PlayerPageStates.tsx`, `components/squad/PlayerForm.tsx`, `lib/squad/form.ts`, `lib/squad/players.ts`, `lib/squad/errorCodes.ts`.
  - Shared: `lib/api/errors.ts`, `i18n/locales/he.json`.
- **Frontend tests as a model:** `pages/stats/StatsPage.test.tsx`, the `NewPlayerPage` tests, `test/msw/` (the stats and squad builders), `test/liveRegion.ts`.
- **`docs/spec.md`:** the status line, §05, §07, §08, §13. Only to list what goes out of date, **not** to edit.

## Tests

### Backend (Testcontainers, real replica set; JUnit, the existing style)

Integration tests for the new endpoint, at least:

1. **Happy path:**
   - `201`, the body is the player, `Location` is `/squad/players/{newId}`;
   - every optional field sent is stored (secondary position, jersey number, height, weight, preferred foot, medical status);
   - the link exists with `linkedAt` from the clock;
   - `GET /matches/player-stats` shows the row with `linkedPlayer` = the new player;
   - `GET /squad/players/{newId}` works;
   - no football.org.il id anywhere in the body.
2. **Minimal body** (only the required fields) → `201`, medical status FIT.
3. **`400`** for each kind of invalid body `POST /squad/players` rejects (blank name, missing primary position, under-age date of birth, same primary and secondary position, …): the same answer as `POST /squad/players` with the same body (compare them), and **nothing** written (player count and link count unchanged).
4. **`403`** for `VIEW_ONLY` and `EDIT_PARTIAL`. Nothing written.
5. **`404`:**
   - an unknown row id;
   - **another club's** row id;
   - an unmapped club;
   - a club with no snapshot.
   No player is created in either club.
6. **`409 STATS_ROW_ALREADY_LINKED`** for a row linked to an existing player: no player created, the existing link unchanged.
7. **A dangling link** (its player deleted directly in the test setup) → `201`, the link now points to the new player, one link document for that row.
8. **Rollback, jersey number:** a taken number → `409 JERSEY_NUMBER_TAKEN`, **no** new player, **no** link.
9. **Rollback, link failure:** make the link write fail **after** the player insert inside the transaction (e.g. a conflicting link for the row inserted between the pre-check and the transaction, through a test seam, or a link document that violates the index) → `409`, and the player insert was **rolled back** (count unchanged). This is the test that proves the transaction is real; it must fail if the `TransactionTemplate` is removed.
10. **Concurrency:** two concurrent requests for the same row (latch, real threads, like `SnapshotWriteConflictIntegrationTest`):
    - exactly one `201`, the other a `409` (`STATS_ROW_ALREADY_LINKED` or `CONCURRENT_MODIFICATION`);
    - exactly **one** new player and **one** link;
    - no `500`.
    Also with a dangling link on the row (two writers replacing the same link document).
11. **Isolation inside the transaction:** the created player's and link's `clubId` are the caller's club, and the other club's data is untouched.
12. **A later ingest** that keeps the row: the link survives, as for any link (KAN-72).
13. **No regression:** `POST /squad/players` and `PUT …/link` tests pass unchanged.

### Frontend (Vitest + MSW, the existing rules)

The existing rules apply: console guard, unmocked-request guard, `test/msw/handlers.ts` stays empty. At least:

**Cell and picker**

- "יצירת שחקן בסגל" in the no-suggestion state:
  - for `EDIT_FULL` / `ADMIN` only;
  - a link to `/app/stats/{id}/new-player`;
  - an accessible name with the scraped name.
- The suggestion state is unchanged.
- The picker for an unlinked row (with and without a suggestion) has the create link. The empty-squad picker shows the new text and the link. "שינוי קישור" on a linked row has **no** create link.
- Below `EDIT_FULL`: no action, no link.

**Form page**

- **States:** loading; read error with retry; no such row; `NOT_MAPPED`; `NO_DATA`; row already linked (the name and the card link); `VIEW_ONLY` / `EDIT_PARTIAL` (the existing `RequireEditFull` behaviour, no `GET`).
- **The form:**
  - the name is pre-filled with `row.name` and editable;
  - the context block and the hint are shown;
  - **every** field of the normal create form is present, with the same required ones;
  - a background stats refetch while typing **doesn't reset** the typed values.
- **Save:**
  - exactly **one** `POST /matches/player-stats/{id}/player`, the body equal to what `/app/squad/new` would send for the same values (compare with `formValuesToCreateBody`);
  - a double click sends one;
  - then navigation to `/app/stats` with the original filter kept, `replace` (Back doesn't return to the form), focus on the row's header, and the announcement in an existing `role="status"` region (`watchLiveInsertion`);
  - the stats are refetched and the squad caches updated as for a normal create.
- **Cancel** → back with the filter, focus on the row, no request.
- **Errors**, each with its text and invalidation rule:
  - `400` (field errors on their fields);
  - `403`;
  - `404`;
  - `409 STATS_ROW_ALREADY_LINKED`;
  - `409 JERSEY_NUMBER_TAKEN` (on the jersey field);
  - `409 CONCURRENT_MODIFICATION` (values kept);
  - `500`;
  - a network error.
- **The return path:** a forged filter value or a forged state URL never leads anywhere but `/app/stats` (with or without `?filter=unlinked`).
- **`/app/squad/new` and the edit page are unchanged:** their existing tests pass **untouched**.

### E2E

The E2E club stays **unmapped** (don't change the seeder or the E2E env). One cheap scenario in `e2e/stats.spec.ts`, as the seeded admin:
- open `/app/stats/some-id/new-player` directly;
- the real `GET /matches/player-stats` answers `200`;
- the "no longer appears" text with the link back is shown, and no form.

### Mutation checks

Break the code on purpose, confirm a test fails, then restore. At least:

**Backend**

1. remove the `TransactionTemplate` (create, then link, no transaction);
2. replace an existing live link instead of refusing it;
3. skip the pre-check of the row's link;
4. build `Location` with `fromCurrentRequest().path("/{id}")`;
5. let the lost-race `DuplicateKeyException` escape;
6. treat a dangling link as a live one.

**Frontend**

7. create through `POST /squad/players` and then `PUT …/link` (two requests) instead of the new endpoint;
8. drop the filter on the way back;
9. show the create action to `VIEW_ONLY`;
10. show the create link in the picker's "שינוי קישור";
11. don't invalidate the stats query after a successful save;
12. key the form on the stats data, so a refetch resets it.

Report each and whether it was caught.

### Commands

- **Backend:** `./mvnw verify` (format / lint as CI runs them). Report the count before and after.
- **Frontend** (Node 22):
  - `npm run lint`, `npm run format:check`, `npx tsc -b`;
  - `npm test` (the count before and after);
  - `npm run build` (report that the new route's chunk is separate from the main bundle);
  - `npm run e2e` if the local stack is up (otherwise say so; CI runs it).

### Manual check

**Gal runs it**, not you. You have no credentials and no browser: don't try to log in. In the summary, write the list of steps for Gal (as an `ADMIN` of the mapped Sderot club, on the dev server):

- create **one real squad player** from an unlinked row;
- check it's linked on return, with the filter kept;
- try a taken jersey number and see that **no** player was created (the squad count is unchanged);
- check that "שינוי קישור" has no create link.

**No refresh against the live site** in this check.

## Docs

- **`README.md`** (rule 7, same commit as the code):
  - the new endpoint in the "Player statistics" API part (body, `201`, every error code, the transaction);
  - the frontend flow in the frontend section.
- **`CLAUDE.md`:**
  - the status line (KAN-77 done);
  - one short note: the create-and-link endpoint is one MongoDB transaction and refuses a linked row, unlike `PUT …/link`, which replaces.
  - Nothing else.
- **Code comments** in the existing style:
  - why it's a transaction and not create-then-delete;
  - why the lost race is decided **after** the abort;
  - why there's no automatic retry;
  - why it refuses where `PUT …/link` replaces;
  - why `PLAYER_ALREADY_LINKED` can't happen here;
  - why the return path takes only a known filter value.
- **`docs/spec.md`: do NOT edit it.** Gal will provide the updated file before the PR (CLAUDE.md rule 7). In the summary, list **every** place that's now out of date, with the section, the sentence (quote a few words) and what's wrong. I expect at least:
  - the status line and §13 (KAN-77 done);
  - §05 (creating a player also from the stats screen, linked in the same step);
  - §07 (the new endpoint, its refusal of a linked row, the transaction, the entry points on the stats screen);
  - §08 (the new route, if §08 lists screens or routes).

  Add any I missed.

## Commits

Small, focused commits, for example:

- `feat(backend): public squad entry point for creating a player (KAN-77)`
- `feat(backend): create a squad player from a scraped row and link it in one transaction (KAN-77)`
- `feat(frontend): create-and-link mutation, error code and test handlers (KAN-77)`
- `feat(frontend): create a squad player from the stats screen (KAN-77)`
- `test(e2e): create-player route for an unmapped club (KAN-77)`

README and CLAUDE.md go with the code they describe (rule 7). Stage files by path, never `git add -A`. **Do not push and do not open a PR.** Do not add this prompt to the repo yet (Gal commits it with the PR).

## Summary to return

1. Branch, commits (hash + message), and every file changed, one line each.
2. `node --version`, `java -version`, the versions verified and **how** you checked each, with the finding for every item in "Verify against reality" (evidence, not assumptions). Include:
   - the raw `201` answer and its headers;
   - the three raw `409` bodies;
   - what a write conflict was translated to.
3. The design:
   - what you made public in `squad`, and why nothing less would do;
   - the service: the order, the transaction, the error mapping;
   - the frontend route, the entry points, the `PlayerForm` extension and why the existing hosts are unaffected;
   - the return path and focus.
4. Tests:
   - the new tests (name + one line each);
   - the mutation checks and their results;
   - the backend and frontend counts before and after;
   - the results of lint, format, tsc, build (chunk names / sizes) and e2e.
5. The manual-check steps for Gal.
6. Docs changed, one line each.
7. Spec: every out-of-date place (section + quoted words + what's wrong).
8. Deviations from this prompt, and why. Include any Hebrew string you changed (rule 3).
9. Open ends and risks.
10. Print in full, at branch HEAD:
    - the new `match` service class (or method) with the transaction;
    - the `squad` public entry point and every visibility change;
    - the new controller method;
    - the new exception class;
    - the frontend route page;
    - the new mutation;
    - the `PlayerForm` change (diff);
    - the `SquadPlayerCell` and `LinkPickerDialog` changes (diff).
