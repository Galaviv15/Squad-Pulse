# KAN-50: Frontend player card and create/edit form, plus a `code` on 409 errors

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `89bf7d0` (the KAN-49 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-50-player-card-form`.

## Context

This is Jira KAN-50, in epic **KAN-43 "Frontend MVP"** (Phase 3). KAN-45 to KAN-49 are merged: infra, API client and session, auth screens, app shell, squad table. KAN-50 was **split** (Gal's decision). This ticket covers four things:

- a small **backend** change: a machine-readable `code` on error responses;
- the **player card** at `/app/squad/:playerId`;
- the **create / edit form** at `/app/squad/new` and a new route, `/app/squad/:playerId/edit`;
- the squad table's **row-actions column**, with "open" and "edit".

Release, reactivate, permanent delete and photo upload / remove are **KAN-59** (blocked by this ticket). Don't build them. Build things so KAN-59 can add them without rewriting: the actions menu, the card's action area and the query invalidation (sections 3, 4 and 6).

Reuse what's there; don't rebuild it:

- `apiJson` / `apiFetch` (`src/lib/api/client.ts`) and `ApiError` (`src/lib/api/errors.ts`).
- `src/lib/squad`: `types.ts` (with its value arrays), `labels.ts`, `age.ts` (`ageOn`), `paths.ts` (`playerPath`, `playerPhotoPath`, `NEW_PLAYER_PATH`), `players.ts` (`SQUAD_PLAYERS_QUERY_KEY`).
- `hasPermission` (`src/lib/auth/permissions.ts`) and `useCurrentUser()`.
- `ImageOrInitials` for the photo. `Badge`, `Button`, `Input`, `Label`, `Card`, `Select` (`src/components/ui`).
- `AuthField`'s pattern (label, hint/error linked by `aria-describedby`, `aria-invalid`, `--danger` border), and `FormAlert` / `FormNotice` (`src/components/auth`). If you generalize any of them for non-auth forms, move them to a neutral place (for example `src/components/form/`), keep the auth screens working unchanged, and say what you moved.
- The route `handle` / `RouteHandle` title mechanism: pages never render their own `<h1>`.
- The pathless `errorElement` (`RouteErrorPage`) and the function-form `lazy` routes in `src/app/router.tsx`.
- Test helpers: `renderWithProviders`, `src/test/msw/auth.ts`, `src/test/msw/squad.ts` (`playerBody`, `playersReturn`, `recordedPlayers`), `deferred()`.

Read these first:

- `CLAUDE.md`: all of it, especially the frontend paragraph and the backend rules on errors and modules. Every rule applies. **A test asserting that no request is sent must spy on `fetch`.**
- `docs/design/ui-conventions.md`: all of it (type scale, density, badges, RTL / LTR islands, `tabular-nums`, "Squad table (KAN-49)"). There's **no mockup** for the card or the form. Follow the conventions and section 4 below, and Gal will judge the look in the manual check.
- `docs/spec.md` sections 05 (player rules), 10 (error responses, concurrent writes) and 13. **Read only. Don't edit the spec** (see "Spec").
- `README.md`: the player field rules and the error-shape table.
- Backend: `squad/PlayerController.java`, `CreatePlayerRequest.java`, `UpdatePlayerRequest.java`, `PlayerResponse.java`, `PlayerService.java` (`create`, `update`, how a stale version and a lost save race both become `StalePlayerVersionException`), every `*Exception.java` in `squad` and `auth` that extends `common.ConflictException`, `DistinctPositions*`, `common/AdultAge*`, `common/ApiErrorResponse.java`, `common/ConflictException.java`, `common/GlobalExceptionHandler.java`, `common/UnhandledExceptionFilter.java`, `auth/SecurityConfig.java` (`writeError`).

## Facts I checked on master `89bf7d0` (re-confirm each against the code; don't take them on faith)

- **Every 409 is indistinguishable by machine.** `GlobalExceptionHandler.handleConflict` answers `{status: 409, error: "Conflict", message: ex.getMessage(), details: []}` for every `ConflictException`. The squad module has five subclasses: `JerseyNumberTakenException`, `StalePlayerVersionException`, `ReleasedPlayerException`, `PlayerAlreadyActiveException` and `PlayerAlreadyReleasedException`. The auth module has four: `EmailAlreadyRegisteredException`, `CannotChangeOwnPermissionLevelException`, `CannotChangeOwnActiveStatusException` and `DeactivatedUserException`. `handleOptimisticLockingFailure` is a separate 409 with `CONCURRENT_MODIFICATION_MESSAGE`. Today the only difference between them is the English message.
- `ApiErrorResponse` is `(timestamp, status, error, message, details)`. Besides the handler, it's built in `SecurityConfig.writeError` and `UnhandledExceptionFilter`.
- `POST /squad/players` (EDIT_FULL) → `201` + `Location` + the `PlayerResponse` (`hasPhoto` always `false`). `PUT /squad/players/{id}` (EDIT_FULL) → `200` + the `PlayerResponse`. `GET /squad/players/{id}` (VIEW_ONLY) → the player, released ones included (`active: false`); another club's or a missing id → `404`.
- **Create** (`CreatePlayerRequest`): `fullName` required (trimmed first, then not blank, at most 100); `primaryPosition` required; `secondaryPosition` optional and ≠ primary (`@DistinctPositions`, reported as a **field error on `secondaryPosition`**); `jerseyNumber` optional, 1–99; `dateOfBirth` required, `yyyy-MM-dd`, age 18–99 today (`@AdultAge`: also invalid if in the future); `heightCm` optional, 140–220; `weightKg` optional, 40–150; `preferredFoot` optional; `medicalStatus` **optional** (server default `FIT`).
- **Edit** (`UpdatePlayerRequest`): a **full replacement**. An optional field that's absent or `null` **clears** the stored value. `medicalStatus` is **required**, and `version` is **required** (the value the client loaded). Unknown fields (`clubId`, `active`, `id`) are ignored.
- 409s on `PUT`: the version is stale (also when the save loses a race) → `StalePlayerVersionException`; the player is released → `ReleasedPlayerException`; the jersey number is taken by another **active** player → `JerseyNumberTakenException` (it's the unique index that decides, so it also happens under a race). `POST` can 409 on the jersey number only.
- 400 bodies: `error: "Validation Failed"`, with one `"<field>: <message>"` per field in `details`. An unreadable value (bad enum or date) gives `"<field>: invalid value"`. A missing body gives `"Malformed request body"` with no field. The frontend `ApiError` already splits these into `fieldErrors` / `generalErrors`.
- `Size(max = 100)` counts UTF-16 units, like JS `string.length`.
- Raw data (KAN-28) can have a player with `null` `primaryPosition` or `dateOfBirth`, or stored values outside today's rules. The card must render them safely, and the edit form makes the user fix them before saving.
- No dialog, dropdown-menu or form library exists in the frontend. Base UI is installed (`@base-ui/react`).

## Decisions (agreed with Gal — implement these)

### 1. Backend: `code` on error responses (its own commit, before any frontend commit)

- Add a `String code` component to `ApiErrorResponse`, placed after `error`: `{timestamp, status, error, code, message, details}`. It's **always present** in the JSON, and `null` when the error has no code. Keep the existing `of(...)` factories, with `code = null`, and add overloads that take a code. Check that Jackson 3.1.5 (the installed version) writes the `null` and doesn't drop it, and say how you checked.
- `ConflictException` gets a required `code`: `ConflictException(String code, String message)`, plus a `getCode()`. Validate it in the constructor against `^[A-Z][A-Z0-9_]*$`, so a typo fails at once. `handleConflict` passes it through. Each subclass declares its code as a `static final String` constant **in its own class** (codes stay owned by their module; don't put a list of squad codes in `common`):
  - `JERSEY_NUMBER_TAKEN`, `STALE_VERSION` (StalePlayerVersionException), `PLAYER_RELEASED` (ReleasedPlayerException), `PLAYER_ALREADY_ACTIVE`, `PLAYER_ALREADY_RELEASED`;
  - `EMAIL_ALREADY_REGISTERED`, `CANNOT_CHANGE_OWN_PERMISSION_LEVEL`, `CANNOT_CHANGE_OWN_ACTIVE_STATUS`, `USER_DEACTIVATED` (DeactivatedUserException).
  - The generic optimistic-lock 409 (`handleOptimisticLockingFailure`) gets `CONCURRENT_MODIFICATION`.
- **No other status gets a code in this ticket.** Codes are additive and stable: once shipped, a code is never renamed. Write that rule into the `ConflictException` Javadoc and CLAUDE.md.
- Messages and statuses are unchanged. Only the new field is added.
- Tests:
  - every 409 path already tested asserts its `code`; at least one test per code, through the real HTTP layer (MockMvc or the integration tests);
  - the optimistic-lock handler's code;
  - representative non-409 errors (400 validation, 401 from `SecurityConfig`, 404, 500 from `UnhandledExceptionFilter`) have `"code": null` **present** as a key;
  - the constructor rejects a malformed code.
  
  If a test asserts the exact key set of an error body, update it and list it.
- Update `README.md`'s error-shape paragraph and table: the new field, the list of codes, and which endpoint returns which code. Update CLAUDE.md's backend rules (a new `ConflictException` subclass must declare a code).
- Run `./mvnw spotless:apply`, then `./mvnw verify` with `JAVA_HOME` = Temurin 21. Report the backend test count before and after.

### 2. Frontend `ApiError.code`

- `ApiErrorBody` gets `code: string | null`. `isApiErrorBody` must accept a body **without** `code` (a missing key is treated as `null`). It must also accept a string or `null`, and must not throw on any other type (treat it as `null`).
- `ApiError` gets `readonly code: string | null`.
- Add the squad codes as constants in `src/lib/squad/` (for example `PLAYER_ERROR_CODES` or separate `const`s): `JERSEY_NUMBER_TAKEN`, `STALE_VERSION`, `PLAYER_RELEASED`. Screens compare against these and **never** against `message`.
- Unit tests in `errors.test.ts`.

### 3. Data layer (`src/lib/squad`)

- **One prefix for every squad query:** export `SQUAD_QUERY_KEY = ["squad"]`. The list stays `["squad", "players", filters]`, keeping `SQUAD_PLAYERS_QUERY_KEY` as an alias or derived constant. The card's query key is `["squad", "player", id]`. Note that `"player"` is not `"players"`, so the list prefix doesn't match the card: check this with a test. KAN-51's summary must be under the same prefix (`["squad", "summary"]`). Write that into CLAUDE.md.
- `usePlayer(id)`: `GET /squad/players/{id}` through `apiJson`, with `encodeURIComponent` on the id.
- `useCreatePlayer()` / `useUpdatePlayer(id)`: TanStack mutations through `apiJson`. Their `onSuccess`:
  - writes the returned player into the card's cache (`setQueryData`), so the card shows it at once;
  - then `invalidateQueries({ queryKey: SQUAD_QUERY_KEY })` (every list, every card, and the future summary).
  
  KAN-59's mutations will follow the same pattern. Put it in one helper (for example `onPlayerWritten(queryClient, player)`) and say its name.
- **Form ↔ API mapping**, as pure functions with unit tests:
  - `playerToFormValues(player)` turns `null` into `""`.
  - `formValuesToCreateBody(values)` and `formValuesToUpdateBody(values, version)`. On **update**, every optional field is sent explicitly: an empty field → `null` (a full replacement clears it). On **create**, `medicalStatus` is always sent (the form preselects `FIT`).
  - Numbers are sent as numbers, never as strings. The name is sent as typed (the server trims it).
- **Client validation**: `validatePlayerForm(values, today, mode)` returns `Record<field, i18nKey>`. It's a pure function mirroring the DTOs exactly (facts above):
  - name: trimmed not empty, ≤100;
  - primary: required;
  - secondary: ≠ primary;
  - numbers: integers by `^\d{1,3}$`, within their range;
  - date: required, valid `yyyy-MM-dd`, not in the future, `ageOn` 18–99;
  - medical: required on edit.
  
  Unit tests cover every boundary: 1/99/0/100 for the jersey number, 139/140/220/221, 39/40/150/151, the 18th and 100th birthday today and the day before, a future date, 100 vs 101 characters, and whitespace-only names. Note in a comment that the browser's date and the server's can differ around midnight. The server's 400 then shows on the field.

### 4. Pages and routes

Routes:

- `/app/squad/:playerId` → `PlayerCardPage`. Title handle: `squad.playerCard` ("כרטיס שחקן"). Lazy.
- `/app/squad/new` → `NewPlayerPage` (replaces the stub). Title: "הוספת שחקן". Lazy.
- **New** `/app/squad/:playerId/edit` → `EditPlayerPage`. Title: "עריכת שחקן". Lazy, with the same function form and a static `handle`.
- Delete `PlayerStubPages.tsx` and its now-unused i18n keys. Verify, with a test against the installed React Router 8.4, that `/app/squad/new` never matches `:playerId` and that `/app/squad/x/edit` matches the edit route. The סגל nav item stays active on all three.

**Permission guard** for `/new` and `/:playerId/edit`:

- If `!hasPermission(permissionLevel, "EDIT_FULL")`, render, inside the page, a "no permission" message ("אין לך הרשאה לפעולה הזו.") and a link back to the squad. Not a redirect.
- Fetch nothing in that case. Spy on `fetch` in the test.

**Player card** (`/app/squad/:playerId`):

- **Header**: a `bg-card` bordered `rounded-lg` panel with:
  - the photo (`ImageOrInitials`, 96px round, `object-cover`, `path` only when `hasPhoto`, fallback icon `User`);
  - the name as `<h2>` (the page's `<h1>` is the route title), 20/700;
  - the jersey number as `#9` in an LTR island, `tabular-nums`;
  - the position chips (primary filled, secondary outlined, `dir="ltr"`), as in the table;
  - the medical pill;
  - for a released player, the muted "משוחרר" pill, with the whole card dimmed (`opacity-60` on the content, not on the action area), as in the conventions.
- **Details**: a definition list (`<dl>`), 2 columns from `md`, 1 below. Fields:
  - תאריך לידה: `dd.MM.yyyy` from the date's parts (never `new Date("yyyy-MM-dd")`), with the age next to it, "(גיל 27)";
  - גובה (ס״מ), משקל (ק״ג), רגל, מצב רפואי;
  - עמדה ראשית / עמדה משנית;
  - מספר חולצה.
  
  `null` → "—" (muted). Use U+05F4 in ס״מ / ק״ג.
- **Action area** (end side of the header, one `div` KAN-59 will add to): "עריכה" (outline `Button`, lucide `Pencil`) is a **link** to the edit route. It's shown only for EDIT_FULL and up **and** an active player. A "חזרה לסגל" link is always shown.
- **States**:
  - loading → `role="status"` "טוען שחקן…";
  - `404` → "השחקן לא נמצא." with a link back to the squad (not the error element);
  - other errors → "לא הצלחנו לטעון את השחקן." with "נסו שוב";
  - a background refetch failing while data is shown keeps the data.

**Form** (one `PlayerForm` component, used by both pages):

- A `bg-card` bordered `rounded-lg` panel. Fields in a 2-column grid from `md`, 1 column below. 40px controls, a visible label on every field, the error under its field (`aria-invalid` + `aria-describedby`). Order:
  - שם מלא (full width);
  - עמדה ראשית, עמדה משנית;
  - מספר חולצה, תאריך לידה;
  - גובה (ס״מ), משקל (ק״ג);
  - רגל, מצב רפואי.
- Required fields are marked visually and with `aria-required`. Number fields are text inputs with `inputMode="numeric"`, like KAN-49's ages, LTR, `tabular-nums`.
- Date of birth: `<input type="date">`. Its value is already `yyyy-MM-dd`. Check how it renders under `dir="rtl"` in Chrome and say what you saw. Give it `max` = today.
- Selects: shadcn `Select`. The optional ones (secondary, foot) have a first option "ללא" that maps to `""`. Position options show the codes in English, in LTR islands, in enum order. For secondary, the primary's code is excluded, or kept but invalid (decide and say). If primary changes to equal secondary, show the secondary's error. Don't clear it silently.
- **Submit**:
  - Validate on submit. After the first submit, validate as the user types. Focus the first invalid field.
  - While saving: the button is disabled with "שומר…", and a second submit is impossible.
  - Buttons: "הוספת שחקן" / "שמירת שינויים" (primary) and "ביטול" (a link: back to the card on edit, to the squad on create).
- **After success**, navigate with **replace**: Back must not return to the filled form.
  - create → the new player's card;
  - edit → the card.
- **Server errors** on submit, in this order:
  - **400** with field errors → each on its field, as a generic translated message per field ("ערך לא תקין"). Never show the backend's English. Field names not in the form, and general errors → a `FormAlert` "לא הצלחנו לשמור. בדקו את הפרטים ונסו שוב."
  - **409 `JERSEY_NUMBER_TAKEN`** → on the jersey field: "המספר תפוס על ידי שחקן פעיל אחר." Focus it.
  - **409 `STALE_VERSION`** → a `FormAlert` "השחקן עודכן על ידי מישהו אחר מאז שפתחת את הטופס." with a button "טעינת הגרסה העדכנית". It refetches the player and resets the form to the server's values, replacing the user's edits. Say that in a hint line under the alert ("השינויים שלך לא יישמרו."). The user's input is kept until they press it.
  - **409 `PLAYER_RELEASED`** → a `FormAlert` "השחקן שוחרר ולא ניתן לערוך אותו." with a link to the card. Also invalidate the squad queries.
  - **Any other 409** (unknown code) → the generic save alert.
  - **403** → "אין לך הרשאה לפעולה הזו." (the level may have dropped since `/me`). **404** on edit → "השחקן לא נמצא." with a link to the squad. **5xx / network** → "לא הצלחנו לשמור. נסו שוב." In every case the form keeps the user's input.
- **Edit page specifics**:
  - Load the player with `usePlayer`. Prefill once from the first load. A background refetch must **not** overwrite what the user typed: don't reset on every data change, and explain how you prevented it.
  - Send the `version` from the load the form was filled from, or from the last stale-reload. Never the newest cache value.
  - Released player → don't show the form: "השחקן שוחרר ולא ניתן לערוך אותו." with a link to the card.
- **Not in scope**: an "unsaved changes" prompt on leaving. Say in your summary whether `useBlocker` in RR 8.4 would make it cheap, for a later ticket.

### 5. Row-actions column (squad table)

- A last column with header "פעולות". Render the visible header text as `sr-only` if that looks cleaner, and say which. Each row gets an icon button (`Button variant="ghost" size="icon-sm"`, lucide `MoreHorizontal` or `EllipsisVertical`, decide) with `aria-label` "פעולות עבור {{name}}". It opens a menu: shadcn `dropdown-menu` (Base UI Menu), under the dependency rule below. Items:
  - "פתיחת כרטיס" (always);
  - "עריכה" (EDIT_FULL+ and active only).
  
  KAN-59 adds release / reactivate / delete. Build the item list as data, so they slot in.
- **Row click must not fire from the menu.** React synthetic events bubble through portals, so a click on a portaled menu item reaches the `<tr>`'s `onClick`. Make `openFromRow` ignore clicks from the trigger and from anything inside the menu: check `event.target` against the trigger and the popup, or stop propagation at the menu. Test both: clicking the trigger doesn't navigate, and clicking "עריכה" goes to the edit route, not the card.
- Check the menu under RTL: the side it opens on and the keyboard arrows. Escape returns focus to the trigger.
- The column is narrow and `text-end`, and its cells are not dimmed for a released row.

### 6. Copy (Hebrew — use these exactly; keys are yours, all in `he.json`)

| Where | Text |
| --- | --- |
| Edit title / button | עריכת שחקן / עריכה |
| Card loading / not found / error | טוען שחקן… / השחקן לא נמצא. / לא הצלחנו לטעון את השחקן. |
| Labels | שם מלא / עמדה ראשית / עמדה משנית / מספר חולצה / תאריך לידה / גובה (ס״מ) / משקל (ק״ג) / רגל / מצב רפואי / גיל |
| Age next to DOB | (גיל {{age}}) |
| None option | ללא |
| Submit | הוספת שחקן / שמירת שינויים / שומר… |
| Cancel / back | ביטול / חזרה לסגל |
| Field errors | שדה חובה / עד 100 תווים / מספר בין 1 ל־99 / גובה בין 140 ל־220 / משקל בין 40 ל־150 / גיל בין 18 ל־99 / תאריך לא תקין / העמדה המשנית חייבת להיות שונה מהראשית / ערך לא תקין |
| Jersey taken | המספר תפוס על ידי שחקן פעיל אחר. |
| Stale | השחקן עודכן על ידי מישהו אחר מאז שפתחת את הטופס. / השינויים שלך לא יישמרו. / טעינת הגרסה העדכנית |
| Released | השחקן שוחרר ולא ניתן לערוך אותו. / לכרטיס השחקן |
| No permission | אין לך הרשאה לפעולה הזו. |
| Save failed | לא הצלחנו לשמור. בדקו את הפרטים ונסו שוב. / לא הצלחנו לשמור. נסו שוב. |
| Actions | פעולות / פעולות עבור {{name}} / פתיחת כרטיס |

The ranges use the maqaf (U+05BE) in "ל־", as KAN-49 did. Check the bytes. Enum → key mappings stay exhaustive `Record`s.

## Out of scope (don't build)

Release / reactivate / delete, and photo upload / replace / remove (KAN-59). An unsaved-changes prompt. Dark mode. Any backend change beyond section 1. Codes on statuses other than 409.

## Tests

Backend tests: as in section 1.

Frontend: Vitest + React Testing Library + MSW, through `renderWithProviders`. Extend `src/test/msw/squad.ts` with builders such as `playerReturns`, `createPlayerReturns`, `updatePlayerReturns` and `conflict(code)`, with request-body capture. `handlers.ts` stays empty. At minimum:

- **Pure functions**: `validatePlayerForm` (all boundaries in section 3), the form ↔ body mappers (update sends `null` for every emptied optional field; numbers as numbers; `version` sent), `ApiError.code` parsing.
- **Card**: every field and "—" for each nullable one, the DOB format and age, the chips with `dir="ltr"`, the released dimming and pill. The edit link is shown for ADMIN / EDIT_FULL on an active player, absent for EDIT_PARTIAL / VIEW_ONLY and for a released player. Loading, 404 and error + retry. A photo request only when `hasPhoto` (spy on `fetch`).
- **Create**:
  - a valid submit sends exactly the expected JSON (assert the whole body) and lands on the new card with replace (Back doesn't return to the form);
  - an invalid submit sends **no** request (spy on `fetch`), shows each error and focuses the first;
  - a server 400 with field errors puts them on their fields;
  - 409 `JERSEY_NUMBER_TAKEN` goes on the jersey field;
  - a double click sends one request (deferred answer).
- **Edit**:
  - prefill, with nulls as empty;
  - a background refetch doesn't overwrite typed input;
  - 409 `STALE_VERSION` → the alert, input kept, then "טעינת הגרסה העדכנית" resets to the new values and the **next** submit sends the **new** `version`;
  - 409 `PLAYER_RELEASED`;
  - a released player opened at `/edit` shows no form;
  - after a successful save the list query and the card are refetched/updated (assert a list request after returning to the squad, or inspect the query cache).
- **Guards**: VIEW_ONLY / EDIT_PARTIAL at `/new` and `/:id/edit` get the message and **no** request (spy on `fetch`). A 403 on submit shows the message.
- **Row actions**: menu items by permission and status; the trigger doesn't navigate; "פתיחת כרטיס" → card; "עריכה" → edit route; keyboard open / Escape.
- **Routing**: `/new` vs `/:id` vs `/:id/edit`, and סגל stays active.

## Manual verification (in Chrome — Safari can't refresh on http://localhost until KAN-56)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary. He isn't a frontend developer: give the terminal commands and what to click and expect. Cover:

- **Setup**: the branch check; `docker compose up -d`; the backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` + `./mvnw spring-boot:run` (it must be **this branch's** backend, for the codes); `npm run dev`.
- **Two extra users**: how to get an EDIT_FULL and a VIEW_ONLY user, with exact console snippets for invite + activation. The reset code is in the backend log (`LoggingEmailSender`). Or say whether changing his own level is simpler (it isn't allowed: a self-change is a 409).
- **Create**: a player through the form; the validation messages; an existing active player's number → the taken message.
- **Edit**: open the same player in two tabs, save in one, then save in the other → the stale alert → reload → save works.
- **Released player**: release one via a console snippet (`POST .../release` with its `version`), then check the card (dimmed, no edit) and `/edit` (message).
- **Permissions**: as VIEW_ONLY, no edit / add, and `/app/squad/new` typed in the URL shows the no-permission message.
- **Row actions**: the menu by mouse and keyboard, and the row click still works.
- **Phone width (375px)** and RTL look of the date input and the menu.

## Constraints

- English everywhere in code, comments and commits (CLAUDE.md rule 2). Every user-visible string goes in `he.json` (rule 6). List all new Hebrew strings in your summary.
- Frontend: `npm run lint`, `npm run format:check`, `npm test`, `npm run build`, all green with zero warnings. Backend: `./mvnw spotless:check` + `./mvnw verify` on Temurin 21, green. Report both test counts before → after (frontend was 394). Report the bundle sizes (main and squad chunks were 337.75 kB / 130.62 kB) and whether the new pages landed in the lazy chunk.
- Grep every new or changed component for physical-direction classes (`ml-`, `mr-`, `pl-`, `pr-`, `left-`, `right-`, `text-left`, `text-right`, `border-l`, `border-r`, `rounded-l`, `rounded-r`, ...) and paste the result. It must be empty.
- **No new npm dependencies.** shadcn components (`dropdown-menu`, and `dialog` only if you need one) are fine **only** if the pinned CLI adds files without touching `package.json` or the lockfile. Check `git diff package.json package-lock.json` after each `npx shadcn add`. If one needs a dependency, stop and ask first. Prettier-format added components and check them under RTL. **No new backend dependencies.**
- Tokens only (no hex, no raw palette colors). No new theme tokens without asking.
- **Verify every library behavior you rely on against the installed versions, not memory**: Jackson 3.1.5 (null serialization of a record component), TanStack Query 5 (`setQueryData` + `invalidateQueries` prefix matching, mutation `isPending`), React Router 8.4 (route ranking, `navigate(..., { replace: true })`, `useBlocker` for the note), Base UI (Menu and Select roles, portal behavior), lucide-react (every icon name), and the date input in jsdom (how you set its value in tests). Say in the summary what you checked and where.
- Small, focused commits in Conventional Commits style with `(KAN-50)`. The backend commit comes first, for example `feat(api): add a machine-readable code to error responses (KAN-50)`. Stage files by path, never `git add -A`.
- **Update `CLAUDE.md` in the same change (rule 7)**:
  - the error `code` rule (every `ConflictException` declares a stable code; never rename one);
  - `SQUAD_QUERY_KEY` and the card key (KAN-51's summary goes under the prefix);
  - the shared write helper;
  - the form's validation and mapping functions;
  - the routes and their guard;
  - row actions as data, plus the portal / row-click rule;
  - replace "`/new` and `/:playerId` stubs until KAN-50" with what's true now.
  
  Add a short "Player card and form (KAN-50)" bullet to `docs/design/ui-conventions.md`'s implementation notes.
- **Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place this ticket makes outdated, or that should now mention it, with line numbers and what's wrong or missing. For example:

- section 10's "Error responses" (the shape now has `code`, and the list of codes);
- section 05's 409 bullets;
- section 02's frontend paragraph (card / form no longer placeholders, the edit route);
- section 13's Phase 3 row;
- the status line.

I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed / deleted.
- Backend: the final `ApiErrorResponse` and `ConflictException`, the code of every subclass, an example 409 JSON body (copied from a real test response), and how `null` serialization was verified.
- The route table (paste it).
- The data layer: keys, hooks, the write helper, `validatePlayerForm`, and the mappers (paste them).
- How the edit form avoids overwriting typed input on refetch, and which `version` it sends.
- How the row-click / portal issue was solved.
- shadcn components added, plus the `package.json` / lockfile diff (must be empty).
- Test counts before → after (backend and frontend), and the new tests by name.
- All new Hebrew strings (key → text).
- The physical-class grep result. Bundle sizes.
- The manual-verification steps for Gal, complete, with the exact console snippets.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about.
