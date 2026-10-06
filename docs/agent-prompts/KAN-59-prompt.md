# KAN-59: Frontend player lifecycle and photo (release, reactivate, delete, photo upload)

## Step 0: Start from a clean, current master

```
git checkout master && git pull
```

Verify that local `master` equals `origin/master`. `git rev-parse master origin/master` must print the same hash, at or after `07e0e2f` (the KAN-50 merge). If it doesn't, stop and report. Then create the branch `feature/KAN-59-player-lifecycle-photo`.

## Context

This is Jira KAN-59, in epic **KAN-43 "Frontend MVP"** (Phase 3). It was split out of KAN-50 (merged in `07e0e2f`). KAN-50 delivered:

- the player card;
- the create / edit form;
- the row-actions menu (open / edit);
- the `code` on 409 errors.

This ticket adds the remaining player actions on top of it:

- **release** and **reactivate** (EDIT_FULL), from the card and from the table's row-actions menu;
- **permanent delete** (ADMIN), from the card and from the row-actions menu;
- **photo upload / replace / remove** (EDIT_FULL, active players only), **on the card only**.

**Frontend only. No backend change.** The backend has been complete since KAN-27 / KAN-29. If you believe a backend change is needed, stop and ask.

Reuse what's there; don't rebuild it:

- `apiJson` / `apiFetch` (`src/lib/api/client.ts`). `apiFetch` already takes `formData` for multipart, and the retry after a refresh reuses it. `ApiError` (`status`, `code`, `fieldErrors`, `generalErrors`) and `NetworkError`.
- `src/lib/squad`: `players.ts` (`SQUAD_QUERY_KEY`, `playerQueryKey`, `playerApiPath`, `onPlayerWritten`, `usePlayer`, `useUpdatePlayer` as the pattern for a mutation), `errorCodes.ts` (`PLAYER_ERROR_CODES`), `paths.ts` (`playerPath`, `editPlayerPath`, `playerPhotoPath`), `types.ts`, `form.ts` (the jersey-number rule in `validatePlayerForm`).
- `components/squad/playerActions.ts` + `PlayerActionsMenu.tsx`. The item list is data. Its comment already says KAN-59 adds a non-link kind.
- `PlayerCardPage.tsx`: the action area is the one `div` marked "KAN-59 adds ... here".
- `components/form/` (`FormField`, `FormAlert` / `FormNotice`, `FIELD_INVALID_CLASSES`), `ImageOrInitials`, `useAuthorizedImage`, `hasPermission`, `useCurrentUser()`.
- `pages/auth/routeState.ts` (`stateFlag`): the existing pattern for a one-shot flag carried in navigation state.
- Test helpers: `renderWithProviders`, `src/test/msw/auth.ts`, `src/test/msw/squad.ts` (`playerBody`, `conflict(code)` and the other builders), `deferred()`.

Read these first:

- `CLAUDE.md`: all of it, especially the frontend paragraph (query keys, `onPlayerWritten`, row actions as data, the portal / row-click rule, error codes). Every rule applies. **A test asserting that no request is sent must spy on `fetch`.**
- `docs/design/ui-conventions.md`: all of it, including the "Player card and form (KAN-50)" notes. There's **no mockup** for the dialogs or the photo controls. Follow the conventions and the decisions below. Gal judges the look in the manual check.
- `docs/spec.md` sections 05 (release / reactivate / delete / photo rules) and 10 (error responses, the `code` list). **Read only** (see "Spec").
- `README.md`: the endpoint table, the photo limits, the error codes.
- Backend: `squad/PlayerController.java` (release, reactivate, delete, photo endpoints and their Javadoc), `ReleasePlayerRequest.java`, `ReactivatePlayerRequest.java`, `PlayerService.java` (`release`, `reactivate`, `delete`, `uploadPhoto`, `deletePhoto`), the squad `*Exception.java` classes, `common/ImageValidator.java`, `common/GlobalExceptionHandler.java` (multipart / size handling), `application.yml` (multipart limits, `max-swallow-size`, `squadpulse.images.max-size`).

## Facts I checked on master `07e0e2f` (re-confirm each against the code; don't take them on faith)

**Release and reactivate**

- `POST /squad/players/{id}/release`: body `{version}` (`@NotNull Long`), EDIT_FULL. Returns `200` + `PlayerResponse`.
- `POST /squad/players/{id}/reactivate`: body `{version, jerseyNumber}`, EDIT_FULL. Returns `200` + `PlayerResponse`.
  - `jerseyNumber` is `@Min(1) @Max(99)` and a **full replacement**: absent or `null` = no number, not "keep the old one".
  - Possible 409 codes: `STALE_VERSION`, `PLAYER_ALREADY_ACTIVE` / `PLAYER_ALREADY_RELEASED`, and `JERSEY_NUMBER_TAKEN` (reactivate only).
- `PlayerResponse` carries `version`, in the list too. The row menu can release / reactivate without loading the card.

**Delete**

- `DELETE /squad/players/{id}`: ADMIN. Returns `204`. Not version-checked. It deletes the photo first, then the player.
- **The Jira ticket says "204 also if already gone". That is only true for a concurrent race.** `PlayerService.delete` calls `get(id)` first, so deleting a player that's **already** gone is a **404**.
  - The frontend must treat a 404 on delete as "already gone": same outcome as success. Remove the player from the cache, refresh the squad queries, and, from the card, navigate to the squad.
  - Show the normal "deleted" notice, or a "the player no longer exists" one; decide and say which.

**Photo**

- `PUT /squad/players/{id}/photo`: multipart, one part named `file`, EDIT_FULL. Returns `204`.
  - JPEG / PNG / WebP **by content** (the declared type and the file name are ignored).
  - `400` for an empty / non-image file or a missing part. `413` above `squadpulse.images.max-size` = `2MB` (Spring `DataSize`, so 2 × 1024 × 1024 bytes).
  - `409 PLAYER_RELEASED` for a released player.
  - The player's `version` is **unchanged**.
- `DELETE /squad/players/{id}/photo`: EDIT_FULL. Returns `204`, also when there was none. `409 PLAYER_RELEASED` for a released player.
- Request size limits (`application.yml`): the multipart request cap is 3MB, and Tomcat's `max-swallow-size` is 20MB. Above roughly 20MB the connection may be cut, which the browser reports as a **`NetworkError`**, not a 413. Confirm this from the config and the comments there. The client-side size check prevents it in practice. The UI must still handle a `NetworkError` on upload.
- Photo `GET` has `Cache-Control: no-store`.

**Frontend state**

- `useAuthorizedImage(path)` is keyed only on `path`. After a replace, the path is the same and `hasPhoto` stays `true`, so **nothing refetches**. The player's `version` doesn't change either, so it can't serve as the key.
- `PLAYER_ERROR_CODES` has only `JERSEY_NUMBER_TAKEN`, `STALE_VERSION` and `PLAYER_RELEASED`. `PLAYER_ALREADY_ACTIVE` and `PLAYER_ALREADY_RELEASED` are missing.
- `onPlayerWritten(queryClient, player)` = `setQueryData` on the card + `invalidateQueries(SQUAD_QUERY_KEY)`. **It must not be used for delete**: invalidating would refetch the deleted player's card while it's still mounted and show the 404 state.
- **There is no dialog component** in `src/components/ui` (only `badge`, `button`, `card`, `direction`, `dropdown-menu`, `input`, `label`, `select`, `table`). `@base-ui/react` ^1.8.0 and `shadcn` ^4.21.1 (style `base-nova`, `rtl: true`) are installed.
- No toast component exists. Don't add one.

## Decisions (agreed with Gal — implement these)

### 1. Dialog infrastructure

- Add shadcn's **`alert-dialog`** (for the confirmations) and, only if you need it for reactivate, **`dialog`**. Decide whether `alert-dialog` is enough for a dialog that has a form field, and say why.
- Rules for adding them:
  - Use the pinned CLI (`npx shadcn add ...`).
  - Check `git diff package.json package-lock.json` after each add. It must be empty. If a component needs a new dependency, stop and ask.
  - Prettier-format the added files.
- Check, against the **installed** Base UI version, and say how you checked:
  - the dialog's roles (`alertdialog` / `dialog`), `aria-labelledby` / `aria-describedby`;
  - focus trap, initial focus, and focus return to the trigger on close;
  - Escape and backdrop click.
- RTL: the footer button order and the close button's side.
- **Opening a dialog from the row-actions menu**: the menu closes when an item is chosen. The dialog must still open, and focus must return somewhere sensible (the row's trigger) when it closes. Base UI menus and dialogs both portal and manage focus. Verify this works and test it.
  - A typical fix is a controlled dialog rendered outside the menu, with the menu item only setting state. Don't nest the dialog inside the menu popup.
- **Row click must not fire** from anything inside a dialog either. The existing `openFromRow` ignores targets outside the `<tr>` in the DOM. Confirm that this covers the portaled dialog, and test it: clicking a dialog button does not navigate to the card.

### 2. Data layer (`src/lib/squad`)

- Add `PLAYER_ALREADY_ACTIVE` and `PLAYER_ALREADY_RELEASED` to `PLAYER_ERROR_CODES`, with the same comment style.
- Mutations, following `useUpdatePlayer`, all through `apiJson` / `apiFetch` with `encodeURIComponent` on the id:
  - `useReleasePlayer()` → `{version}`;
  - `useReactivatePlayer()` → `{version, jerseyNumber}`;
  - `useDeletePlayer()`;
  - `useUploadPlayerPhoto()` → `FormData` with part `file`;
  - `useRemovePlayerPhoto()`.
  
  Decide whether the id is a hook argument or a mutation variable (the row menu acts on many players), and say why.
- **After success:**
  - release / reactivate → `onPlayerWritten(queryClient, player)`;
  - photo upload / remove → the response is `204` with no player, so `invalidateQueries(SQUAD_QUERY_KEY)` (the card's `hasPhoto` and the list's photos refresh), plus the photo refresh key below;
  - delete (also on a 404, see facts) → `cancelQueries` + `removeQueries` for that player's card key, **then** invalidate `SQUAD_QUERY_KEY`. Put this in one helper (for example `onPlayerDeleted(queryClient, id)`), and test that no `GET /squad/players/{id}` is sent after a delete.
- **After a 409 that means the cache is stale** (`STALE_VERSION`, `PLAYER_ALREADY_*`, `PLAYER_RELEASED`): invalidate `SQUAD_QUERY_KEY`, as `useUpdatePlayer` does for `PLAYER_RELEASED`.
- **Photo refresh after a replace**: give `useAuthorizedImage` (and `ImageOrInitials`) an optional refresh key, so the same path with a new key refetches. Keep the existing guarantees: the old object URL is revoked, a late answer is ignored, `null` fetches nothing. Existing callers must not change behavior. Where the key lives is your call: card-local state, or a small per-player counter that the table's photos read too. Say what you chose and what happens to the table's thumbnail after a replace on the card.
- **Client-side photo checks** (pure function, unit-tested), before any request:
  - type by `file.type` ∈ `image/jpeg`, `image/png`, `image/webp`;
  - size `> 2 * 1024 * 1024` bytes → rejected; exactly 2 MiB is allowed (confirm the backend's comparison is `>`, not `>=`, and match it);
  - an empty file → rejected.
  
  The server still decides by content. A file that passes here but isn't really an image gets the server's 400 message.
- The file input gets `accept="image/jpeg,image/png,image/webp"`.

### 3. Permissions (what's rendered)

| Action | Shown when |
| --- | --- |
| Release | EDIT_FULL+ and the player is active |
| Reactivate | EDIT_FULL+ and the player is released |
| Delete permanently | ADMIN only, active or released |
| Upload / replace / remove photo | EDIT_FULL+ and the player is active (remove only when `hasPhoto`) |

- Below the level, the control isn't rendered at all. Not disabled.
- A 403 is still handled in every dialog: "אין לך הרשאה לפעולה הזו." (the level may have dropped since `/me`).
- In `playerActions.ts`, add the new items as a **non-link kind** of `PlayerAction` (a discriminated union: `to` for links, an action key for the in-place ones).
- Menu order: open card, edit, release / reactivate, then a separator, then delete (styled destructive).

### 4. Card (`PlayerCardPage`)

**Action area** (the existing `div`):

- edit (exists);
- release **or** reactivate (outline buttons);
- delete (destructive variant, ADMIN only);
- "חזרה לסגל".

Keep it from wrapping badly at 375px, and say how it looks.

**Photo controls**, next to / under the 96px photo:

- Buttons "העלאת תמונה" (no photo) or "החלפת תמונה" (has photo), and "הסרת תמונה" (has photo). They open a hidden `<input type="file">` with a visible label / button; the input itself is keyboard-reachable through the button.
- While uploading or removing, the buttons are disabled and an in-progress state shows on the photo ("מעלה…" / "מסיר…", `role="status"`).
- Remove asks for confirmation (alert-dialog). Upload / replace doesn't.
- Errors show inline under the photo, as a `FormAlert`:
  - client type / size checks → their messages;
  - server 400 → "הקובץ אינו תמונה תקינה. אפשר להעלות JPEG,‏ PNG או WebP.";
  - 413 → the size message;
  - 409 `PLAYER_RELEASED` → the released message, and invalidate the cache;
  - 403 → no permission;
  - network / 5xx → generic.
- After a successful replace, the **new** image shows without a page reload. Test this.
- A released player shows no photo controls, and the photo stays dimmed with the content, as today.

### 5. Dialogs

All dialogs share these rules:

- The confirm button shows a pending label and is disabled while the request runs. A double click sends **one** request.
- The dialog can't be closed while the request is pending (no Escape, no backdrop click, no cancel), so a result never lands on a closed dialog.
- On success: release / reactivate close the dialog and the card re-renders from the returned player. Delete is below.
- Errors show **inside** the dialog. The dialog stays open, except where stated otherwise.

**Release**

- Title: "שחרור {{name}}".
- Text: "השחקן יסומן כמשוחרר ויוסתר מרשימת השחקנים הפעילים. המספר שלו יתפנה לשחקנים אחרים. אפשר להחזיר אותו לפעילות בכל עת."
- Buttons: "שחרור" / "ביטול".
- Sends the `version` of the data the dialog was opened from (card or row).

**Reactivate**

- Title: "החזרה לפעילות של {{name}}".
- One field: "מספר חולצה", prefilled with the old number (empty if none). Same input style and rule as the form (`^\d{1,3}$`, 1–99, empty = no number). Reuse `validatePlayerForm`'s jersey rule or extract it, without duplicating the logic.
- Hint: "השאירו ריק כדי להחזיר את השחקן בלי מספר."
- `JERSEY_NUMBER_TAKEN` → on the field: "המספר תפוס על ידי שחקן פעיל אחר." Focus it. The user can change it and retry.
- A server 400 on `jerseyNumber` → "מספר בין 1 ל־99" on the field.
- Buttons: "החזרה לפעילות" / "ביטול".

**Delete**

- Title: "מחיקה לצמיתות של {{name}}".
- Text: "הפעולה תמחק את השחקן ואת התמונה שלו לצמיתות, ולא ניתן לבטל אותה. שחקן שעזב את הקבוצה — עדיף לשחרר."
- Confirm: destructive "מחיקה לצמיתות" / "ביטול".
- No typed-name confirmation (Gal's decision).
- On success, or on a 404:
  - **from the card** → navigate to `/app/squad` with **replace** (Back must not return to the deleted card), carrying a one-shot navigation-state flag. The squad page then shows a `FormNotice` above the table: "{{name}} נמחק לצמיתות." The name comes from the state. The notice must not reappear on reload or Back; check this against the installed React Router 8.4 and say how, following `routeState.ts`'s pattern;
  - **from the table** → close the dialog and show the same notice above the table (local state). The row disappears after the refetch.

**Stale and already-in-state** (release / reactivate)

- `STALE_VERSION` → "השחקן עודכן על ידי מישהו אחר בינתיים." with a button "טעינת הנתונים העדכניים". It closes the dialog and refetches (card: refetch the card; table: the list refetches from the invalidation).
- `PLAYER_ALREADY_RELEASED` → "השחקן כבר משוחרר.", and `PLAYER_ALREADY_ACTIVE` → "השחקן כבר פעיל.". Each with the same reload button.

**Other errors, in every dialog**

- 403 → no permission.
- 404 (release / reactivate / photo) → "השחקן לא נמצא." with a link to the squad.
- Unknown 409 code / 5xx / network → "הפעולה נכשלה. נסו שוב."

### 6. Copy (Hebrew — use these exactly; keys are yours, all in `he.json`)

| Where | Text |
| --- | --- |
| Actions (buttons / menu) | שחרור / החזרה לפעילות / מחיקה לצמיתות |
| Pending labels | משחרר… / מחזיר… / מוחק… |
| Release dialog | שחרור {{name}} / (text in section 5) |
| Reactivate dialog | החזרה לפעילות של {{name}} / מספר חולצה / השאירו ריק כדי להחזיר את השחקן בלי מספר. |
| Delete dialog | מחיקה לצמיתות של {{name}} / (text in section 5) |
| Deleted notice | {{name}} נמחק לצמיתות. |
| Cancel | ביטול |
| Stale / state | השחקן עודכן על ידי מישהו אחר בינתיים. / טעינת הנתונים העדכניים / השחקן כבר משוחרר. / השחקן כבר פעיל. |
| Generic failure | הפעולה נכשלה. נסו שוב. |
| Photo buttons | העלאת תמונה / החלפת תמונה / הסרת תמונה |
| Photo progress | מעלה… / מסיר… |
| Photo remove dialog | הסרת התמונה של {{name}} / התמונה תוסר מהכרטיס. / הסרה |
| Photo errors | אפשר להעלות רק JPEG,‏ PNG או WebP. / הקובץ גדול מדי. הגודל המרבי הוא 2MB. / הקובץ ריק. / הקובץ אינו תמונה תקינה. אפשר להעלות JPEG,‏ PNG או WebP. |

Reuse the existing keys for: no permission, "השחקן לא נמצא.", "השחקן שוחרר ולא ניתן לערוך אותו.", "המספר תפוס על ידי שחקן פעיל אחר.", "מספר בין 1 ל־99". Keep the maqaf (U+05BE) in "ל־". Format codes inside Hebrew text ("2MB", "JPEG") must not reorder badly under RTL. Check it in Chrome and say what you saw.

## Out of scope (don't build)

- Photo in the create / edit form.
- Cropping or resizing images, or drag-and-drop (a plain file picker is enough).
- A toast system.
- Bulk actions.
- An unsaved-changes prompt.
- Any backend change.
- Dark mode.

## Tests

Vitest + React Testing Library + MSW, through `renderWithProviders`.

- Extend `src/test/msw/squad.ts` with builders, capturing the request body:
  - `releaseReturns`, `reactivateReturns`, `deleteReturns`;
  - `photoUploadReturns` (capture the multipart part name, file name and size);
  - `photoDeleteReturns`.
- `handlers.ts` stays empty.
- Remember CLAUDE.md's jsdom FormData / File note: build upload FormData in tests via `new Response(...).formData()` where needed.

At minimum:

**Pure functions**

- the photo checks: each type; 0 bytes; exactly 2 MiB; 2 MiB + 1;
- the jersey rule as used by reactivate;
- the action list for every permission level × active / released (the table in section 3).

**Release** (card and row menu)

- sends exactly `{version}`;
- the card re-renders as released;
- the list refetches;
- `STALE_VERSION` and `PLAYER_ALREADY_RELEASED` → messages + reload;
- double click → one request (deferred);
- Escape / cancel → no request (spy on `fetch`).

**Reactivate**

- the number is prefilled;
- clearing it sends `jerseyNumber: null` (assert the whole body);
- `JERSEY_NUMBER_TAKEN` on the field, then change and retry succeeds;
- invalid input → no request;
- `PLAYER_ALREADY_ACTIVE`.

**Delete**

- ADMIN only (absent for EDIT_FULL / VIEW_ONLY);
- from the card: replace-navigate to the squad, the notice shows, and **no** `GET` of the deleted player afterwards;
- 404 treated as gone;
- from the row: the notice, and the row is gone after the refetch;
- the notice doesn't survive a re-render from history.

**Photo**

- upload with no photo → `PUT` with part `file`, then the new image shows;
- **replace → the image is fetched again for the same path and the new blob shows** (the key test);
- remove → confirmation → `DELETE`, then initials;
- client-side rejections send no request;
- server 400 / 413 / 409 `PLAYER_RELEASED` / `NetworkError` messages;
- no controls for VIEW_ONLY or a released player;
- the existing `useAuthorizedImage` tests still pass unchanged, plus new tests for the refresh key (same path + new key refetches, old URL revoked, late answer ignored).

**Menu + dialog interaction**

- choosing release in the row menu opens the dialog;
- dialog buttons don't trigger the row's navigation;
- focus returns to the row's trigger on close;
- keyboard: open the menu → item → dialog → Escape.

**403** in at least one dialog and in photo upload.

## Manual verification (in Chrome — Safari can't refresh on http://localhost until KAN-56)

You can't drive a browser, so write **exact, numbered steps for Gal** in your summary. He isn't a frontend developer: give the terminal commands and what to click and expect. Cover:

- **Setup**: `docker compose up -d`; the backend with `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` + `./mvnw spring-boot:run`; `npm run dev` on the branch.
- **Users**: ADMIN, EDIT_FULL and VIEW_ONLY. Reuse the KAN-50 invite / activation snippets, or say if he already has them.
- **Release, then reactivate** from the card and from the row menu, including a reactivate with a number another active player holds.
- **Stale**: open the same player in two tabs, release in one, release in the other → "already released"; edit in one tab, release in the other → stale.
- **Delete** from the card (lands on the squad with the notice; Back doesn't return to it) and from the row.
- **Photo**: upload a JPEG, replace with a PNG (the new one shows at once, and in the table thumbnail after going back), remove. Try a PDF renamed to `.jpg` (server 400) and a file over 2MB (client message).
- **VIEW_ONLY sees none of the controls.** EDIT_FULL sees no delete.
- **375px width**: the card's action area and the dialogs.

## Constraints

**Language and copy**

- English in code, comments and commits (CLAUDE.md rule 2).
- Every user-visible string goes in `he.json` (rule 6). List all new Hebrew strings in your summary.

**Checks**

- `npm run lint`, `npm run format:check`, `npm test` and `npm run build`, all green with zero warnings.
- Report the frontend test count before → after (it was 549 on master).
- Report the bundle sizes before / after, and whether the new code landed in the squad chunks, not the main one.

**Styling**

- Grep every new or changed component for physical-direction classes (`ml-`, `mr-`, `pl-`, `pr-`, `left-`, `right-`, `text-left`, `text-right`, `border-l`, `border-r`, `rounded-l`, `rounded-r`, ...) and paste the result. It must be empty. If an added shadcn component brings any, convert them to logical ones and say so.
- Tokens only (no hex, no raw palette colors). No new theme tokens without asking. The destructive style uses the existing `--danger` / `destructive` token; check which one exists.

**Dependencies**

- **No new npm dependencies.** The shadcn rule is in section 1.

**Verify library behavior against the installed versions, not memory**

Say in the summary what you checked and where:

- Base UI (AlertDialog / Dialog focus and roles, Menu → Dialog handoff, portals);
- TanStack Query 5 (`removeQueries` / `cancelQueries` vs `invalidateQueries`, mutation `isPending`);
- React Router 8.4 (`navigate` with `replace` + `state`, and how state behaves on reload / Back);
- jsdom (File / FormData in tests, `URL.createObjectURL`);
- lucide-react (every icon name).

**Commits**

- Small, focused commits in Conventional Commits style with `(KAN-59)`.
- Stage files by path, never `git add -A`.

**Documentation (rule 7)**

Update `CLAUDE.md` in the same change:

- the dialog components and the menu → dialog pattern;
- the write helpers (including the delete helper, and why delete must not use `onPlayerWritten`);
- the photo refresh key;
- the action kinds in `playerActions.ts`;
- replace any "KAN-59 adds..." wording with what's true now.

Add a short "Player actions and photo (KAN-59)" bullet to `docs/design/ui-conventions.md`'s implementation notes (dialogs, destructive style, photo controls).

**Don't push and don't open a PR.** Stop after committing locally.

## Spec

**Don't edit `docs/spec.md`.** In your summary, list every place this ticket makes outdated, or that should now mention it, with line numbers and what's wrong or missing. For example:

- section 02's frontend paragraph ("Releasing, re-activating, permanently deleting and uploading a photo in the frontend are KAN-59", and the row-actions sentence);
- section 13's Phase 3 row;
- the status line.

I'll prepare the spec update.

## Summary to report back

- Branch, commits (hash + message), files added / changed / deleted.
- shadcn components added, plus the `package.json` / lockfile diff (must be empty).
- The data layer: new hooks, the delete helper, the error-code additions, the photo check function and the refresh-key change to `useAuthorizedImage` (paste them).
- How the menu → dialog handoff and focus return were solved, and how row clicks are kept out of dialogs.
- How the deleted notice is carried and why it doesn't reappear on reload / Back.
- What happens on a delete 404, and which notice shows.
- Test counts before → after, and the new tests by name.
- All new Hebrew strings (key → text).
- The physical-class grep result. Bundle sizes.
- The manual-verification steps for Gal, complete, with any console snippets.
- Every version-specific fact you relied on, and how you verified it.
- Spec places to update. Open ends, risks, anything you weren't sure about.
