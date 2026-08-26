# Voice control for CalWrite (Assistant + Gemini), with a recent-events ledger

> **Status:** planned — not yet implemented.
> **Tracked in:** [ROADMAP.md](../../ROADMAP.md)

## Context

CalWrite is a stopwatch-to-calendar app: you start a timed event, stop it, and it lands in
Google Calendar. Today every one of those actions requires unlocking the phone and tapping.
The goal is to drive the core actions by voice — start, stop, record-instant, extend, and "what
am I tracking?" — from whichever assistant the user has set.

Google has split this surface in two, and the two halves do not overlap:

- **Google Assistant** consumes App Actions declared in `res/xml/shortcuts.xml` (built-in
  intents + dynamic shortcuts). Still documented and supported (docs last updated Feb 2026),
  and it works today.
- **Gemini** ignores Assistant shortcuts entirely. Its replacement is **AppFunctions**
  (`androidx.appfunctions`, alpha, `@RequiresApi(36)`), which is in *private preview with
  trusted testers*. Gemini cannot yet invoke third-party AppFunctions outside adb testing, and
  the full pipeline is limited to Pixel 10 / Galaxy S26 Ultra.

So: build the Assistant path for real, build the AppFunctions path behind a flag so we're wired
up when Google opens the gate, and route both through one shared action layer so the third
surface (whatever it turns out to be) is a thin adapter.

Two things surfaced during exploration that change the shape of the work:

1. **`EventRepository.markEventAsSynced()` is a no-op stub** (`EventRepository.kt:363`) and
   `Event` has no `calendarEventId` column. Calendar event IDs are returned by
   `syncEventToCalendarWithResult()` and then thrown away. Undo, adjust, and extend all need to
   reach back into the calendar copy, so persisting that ID is a prerequisite, not a nicety.
2. **`CompletedEvent` / `CompletedEventDao` are dead code** — they are not in
   `CalWriteDatabase`'s `entities` list and there is no `completedEventDao()` accessor. The real
   record of completed events is the `events` table (`Event` with `endTime != null`). The ledger
   uses `events`; the dead files are left alone (flagged separately, not deleted here).

## Phase 0 — Persist calendar event IDs (prerequisite)

Everything downstream depends on this.

- `data/database/Event.kt`: add `val calendarEventId: Long? = null`.
- `data/database/CalWriteDatabase.kt`: bump `version` 9 → 10, add `MIGRATION_9_10`
  (`ALTER TABLE events ADD COLUMN calendarEventId INTEGER`), register it in `ALL_MIGRATIONS`.
  Follow the numbered recipe already written in the comment block at the top of that companion
  object, including committing the generated schema JSON under `app/schemas/`.
- `data/database/EventDao.kt`: add
  - `@Query("UPDATE events SET calendarEventId = :calendarEventId WHERE id = :id") suspend fun setCalendarEventId(...)`
  - `@Query("SELECT * FROM events WHERE endTime IS NOT NULL ORDER BY endTime DESC LIMIT :limit") fun getRecentCompletedEventsWithType(limit: Int): Flow<List<EventWithType>>`
  - `@Query("SELECT * FROM events WHERE endTime IS NOT NULL AND (:eventTypeId IS NULL OR eventTypeId = :eventTypeId) ORDER BY endTime DESC LIMIT 1") suspend fun getLastCompletedEvent(eventTypeId: Long?): Event?`
  - `@Query("SELECT * FROM events WHERE endTime > startTime AND (:eventTypeId IS NULL OR eventTypeId = :eventTypeId) ORDER BY endTime DESC LIMIT 1") suspend fun getLastTimedEvent(eventTypeId: Long?): Event?`
    — the extend target; the `endTime > startTime` predicate is what excludes instant events.
- `data/EventRepository.kt`: implement `markEventAsSynced` for real (it is already called from
  `syncEventToCalendarWithResult`, `EventRepository.kt:1376` — one line change makes the whole
  chain work). Drop the stale "placeholder" comments and the `getUnsyncedEvents()` stub that
  returns `emptyList()`.

## Phase 1 — Shared action layer

New `data/VoiceActionHandler.kt` — the single entry point every surface calls. No Android UI
dependencies, so shortcuts, App Actions, AppFunctions, and the ledger all reuse it.

```kotlin
sealed interface VoiceActionResult {
    data class Success(val message: String, val eventId: Long?, val undo: UndoToken?) : VoiceActionResult
    data class Ambiguous(val candidates: List<EventType>) : VoiceActionResult
    data class Failure(val message: String) : VoiceActionResult
}
```

Operations: `startTimed(name)`, `stopTimed(name?)`, `recordInstant(name)`, `extendLast(name?)`,
`describeOngoing()`.

**Name resolution** is the crux — event types are user-defined free text. Resolve against
`EventRepository.getAllEventTypesSync()` in order: exact → case/whitespace-normalized →
unique prefix → unique substring. More than one hit returns `Ambiguous` and the drawer asks.
Reuse `getEventTypeByName()` (`EventRepository.kt:93`) for the exact-match fast path.

Respect existing invariants: `startEvent()` already throws `IllegalStateException` when a type
is already running (`EventRepository.kt:409`), and `stopEvent()` throws when no calendar is
selected (`EventRepository.kt:462`). Catch both and convert to `Failure` with a spoken-friendly
message rather than crashing a headless activity.

### `extendLast` — new repository operation

`EventRepository.extendLastEvent(eventTypeId: Long?, calendarRepository): ExtendResult?`

- Find the most recent **completed timed** event — `endTime > startTime`, i.e. `Event.isCompleted()`
  (scoped to type when given). Instant events are excluded, not converted.
- Set `endTime = System.currentTimeMillis()`; no-op if that isn't after the existing `endTime`.
- If `calendarEventId != null`, call `CalendarRepository.updateCalendarEvent(...)`
  (`CalendarRepository.kt:135`); otherwise re-sync via `syncEventToCalendarWithResult` and
  persist the new ID.
- Return the previous `endTime` so the ledger and the undo affordance can revert it.

**Instant events are not extendable.** The SQL predicate is `endTime > startTime`, so an instant
event is invisible to extend even when it is the type's most recent occurrence — in that case the
handler returns `Failure` ("Coffee's last entry was an instant event — nothing to extend"), and
the ledger hides the Extend action on instant rows. Concretely this also means `extendLast` skips
past instant events to the last genuinely timed one rather than refusing outright when the type
mixes cadences; for a `Cadence.INSTANT` type there is simply never a target.

No staleness cutoff is enforced — the result message always states the resulting duration, and
the ledger makes it reversible.

## Phase 2 — Assistant surface (works today)

- **`res/xml/shortcuts.xml`** (new) with capabilities, referenced from a
  `<meta-data android:name="android.app.shortcuts">` on `MainActivity` in `AndroidManifest.xml`:

  | BII | Action |
  |---|---|
  | `actions.intent.START_EXERCISE` | start timed |
  | `actions.intent.STOP_EXERCISE` | stop |
  | `actions.intent.RESUME_EXERCISE` | extend (closest shipped verb) |
  | `actions.intent.TRACK_EXERCISE` | record instant |
  | `actions.intent.OPEN_APP_FEATURE` | generic fallback + "what am I tracking" |

  Each binds `exercise.name` → an intent extra on the new activity below.

- **`ui/voice/VoiceActionActivity.kt`** (new, exported, `Theme.CalWrite.Translucent`,
  `excludeFromRecents`, `documentLaunchMode="always"`). Handles `ACTION_VIEW` plus a
  `calwrite://action/{start|stop|record|extend|status}?name=…` deep link. Parses, calls
  `VoiceActionHandler`, renders the drawer, finishes. Modeled directly on the existing
  `ui/notifications/NotificationActionActivity.kt`, which already does exactly this shape
  (translucent, no layout, reaches repositories through `application as CalWriteApplication`).
  The deep link doubles as the adb test harness and as a Tasker/automation hook.

- **`ui/voice/VoiceShortcutPublisher.kt`** (new). Pushes one dynamic shortcut per event type via
  `ShortcutManagerCompat.pushDynamicShortcut`, each carrying
  `addCapabilityBinding("actions.intent.START_EXERCISE", "exercise.name", listOf(type.name))`.
  This inline inventory is what makes *custom* type names resolve by voice, and it also yields
  launcher long-press shortcuts for free. Called from `CalWriteApplication.onCreate()` and
  whenever event types change.

  Two constraints: the system caps dynamic shortcuts (~15) — publish by `EventType.sortOrder`
  then recency; and the IDs must not collide with the `event_ongoing_${eventType.id}` bubble
  shortcuts already pushed from `NotificationService.kt:154`. Prefix these `voice_start_…` /
  `voice_stop_…`.

**Caveat worth stating up front:** `START_EXERCISE` is a Health & Fitness BII, so Assistant
phrase-matches exercise-shaped utterances. For a type like "Coffee" the reliable phrasing is the
shortcut-label route — *"Hey Google, start Coffee on CalWrite"* — not a natural-language
sentence. That is a Google limitation, not something we can code around.

## Phase 3 — Result drawer (the Lens-style popup)

`ui/voice/VoiceResultSheet.kt` — a `BottomSheetDialog` hosted by the translucent
`VoiceActionActivity`. `Theme.CalWrite.Translucent` already exists (`values/themes.xml:14`) and
is used the same way by the notification activity, so there's a working precedent.

Renders three cases:
- **Confirmation** — "Started Exercise" / "Stopped Exercise · 42m · saved to calendar", with
  contextual buttons (Stop, Extend, Undo, Open app).
- **Query answer** — the `describeOngoing()` result: each running type with live elapsed time.
  This is what makes the query action useful on the Assistant path, which cannot speak a
  response back.
- **Disambiguation** — the `Ambiguous` candidate list as tappable rows.

Auto-dismisses after a few seconds for confirmations (hands-free), stays open for queries and
disambiguation. Dismissal finishes the activity.

## Phase 4 — Stop-behavior setting

- `data/StoragePreferences.kt`: `enum class VoiceStopBehavior { SAVE_SILENTLY, CONFIRM_DIALOG, SAVE_WITH_UNDO }`
  with a `fromName()` companion, following the existing `EventViewMode` / `CardColorStyle`
  pattern (`StoragePreferences.kt:30`, `:43`), plus `KEY_VOICE_STOP_BEHAVIOR` and its
  getter/setter. Default `SAVE_SILENTLY`.
- `SAVE_SILENTLY` → `stopEvent()` + drawer confirmation.
  `CONFIRM_DIALOG` → reuse `ui/dialogs/SaveEventDialog` exactly as `NotificationActionActivity`
  does. `SAVE_WITH_UNDO` → save, then drawer + a notification carrying an Undo action.
- `res/layout/fragment_settings.xml`: new "Voice & Assistant" section with a `RadioGroup`,
  mirroring the existing radio-group section at line 176, plus `setupVoiceSection()` in
  `SettingsFragment.kt` alongside `setupBubbleSection()` (`:205`).

## Phase 5 — Ledger

New overflow entry beside "Manage events" and "Settings".

- `res/menu/menu_main.xml`: add `action_ledger` ("Recent events"); handle it in
  `MainActivity.onOptionsItemSelected` (`MainActivity.kt:259`) next to the existing
  `action_manage_events` / `action_settings` branches.
- `res/navigation/nav_graph.xml`: add `ledgerFragment` + an action from `trackingFragment`.
- `ui/ledger/LedgerFragment.kt`, `LedgerViewModel.kt`, `res/layout/fragment_ledger.xml`,
  `res/layout/item_ledger_event.xml`.

List is recent completed events, newest first, day-grouped — reuse the existing
`ui/tracking/SectionHeaderAdapter.kt` + `ConcatAdapter` approach rather than a new grouping
mechanism. Each row: type color dot (`EventType.getDisplayColor()`), name, start–end, duration,
and a synced/unsynced badge driven by the new `calendarEventId`.

Row actions:
- **Delete/Undo** — remove the `Event` and call `CalendarRepository.deleteCalendarEvent`
  (`:170`). Swipe-to-delete with a Snackbar undo window.
- **Extend to now** — `extendLastEvent` scoped to that row.
- **Adjust** — Material time/date pickers for start and end, then `EventDao.updateEvent` +
  `CalendarRepository.updateCalendarEvent` (`:135`). Notes editable in the same sheet.

This screen is also where the `SAVE_WITH_UNDO` notification's Undo action lands.

## Phase 6 — AppFunctions / Gemini (flag-gated, off by default)

Gated on a Gradle property so the alpha dependency never touches a default build. Implemented;
what follows is what actually worked, which differs from the initial sketch:

- `gradle.properties`: `calwrite.appfunctions=false`.
- With the flag on, `app/build.gradle.kts` declares a single `appfunctions` product flavor. That
  was chosen over hand-wiring an extra source directory because a flavor source set picks up
  sources, resources, and a manifest fragment automatically; with exactly one flavor declared,
  `assembleDebug` still works as the aggregate task. With the flag off no flavor exists at all,
  so nothing is resolved or built.
- `app/src/appfunctions/java/.../CalWriteAppFunctions.kt`: `@AppFunction`-annotated suspend
  methods `startEvent` / `stopEvent` / `recordEvent` / `extendEvent` / `whatAmITracking`, each a
  thin delegate to `VoiceActionHandler`.

Three things the docs did not tell us, found by building it:

- **Pinned to `1.0.0-alpha08`.** `alpha09` and `alpha10` raise `minCompileSdk` to **37** and
  require **AGP 9.1**; this project is on compileSdk 36 / AGP 8.13.2. Upgrading the whole app's
  toolchain for a feature that cannot be invoked yet is the wrong trade, so the last version
  compatible with compileSdk 36 is used instead. Revisit when the project moves to AGP 9.
- **No manifest entry is needed.** alpha08's library declares `PlatformAppFunctionService` and
  `ExtensionAppFunctionService` in its own manifest, so the app declares nothing. (The
  entry-point/`AppFunctionServiceEntryPoint` model in the docs is the alpha09+ shape.)
- **`appfunctions-service` must be requested explicitly.** It appears in `appfunctions`'s pom but
  without a scope, so it is not a real transitive dependency — and the `@AppFunction` annotation
  lives in it.

**Room moved from kapt to KSP as part of this** (the plan had deferred it). kapt reads Kotlin
metadata only up to version 2.0.0 and fails outright on the AppFunctions artifacts, which are
built with Kotlin 2.1: *"Provided Metadata instance has version 2.1.0, while maximum supported
version is 2.0.0"*. Migrating Room to KSP fixes that, removes the
*"Kapt currently doesn't support language version 2.0+, falling back to 1.9"* fallback from every
build, and leaves the exported schema JSON byte-identical.

Verified: the default build is untouched and the flag-on build succeeds, with the KSP compiler
generating the invoker, inventory, and function IDs for all five functions, and packaging
`assets/app_functions_schema.xsd` into the APK.

Expectation setting stands: this compiles and indexes, and can be exercised with the adb
AppFunctions utilities, but **Gemini will not invoke it** until Google widens the preview. It is
future-proofing, not a working feature.

## Verification

- `./gradlew assembleDebug` then `./gradlew installDebug`.
- **Migration:** install the current build first, create a few events, then upgrade — confirm no
  crash and that the new schema JSON is generated and committed.
- **Deep links (works regardless of assistant):**
  ```
  adb shell am start -a android.intent.action.VIEW -d "calwrite://action/start?name=Exercise"
  adb shell am start -a android.intent.action.VIEW -d "calwrite://action/stop?name=Exercise"
  adb shell am start -a android.intent.action.VIEW -d "calwrite://action/extend"
  adb shell am start -a android.intent.action.VIEW -d "calwrite://action/status"
  ```
- **Assistant:** verify the shortcut route by voice ("Hey Google, start Exercise on CalWrite");
  verify BII bindings with Android Studio's App Actions Test Tool.
- **Calendar round-trip:** start → stop → confirm the event in Google Calendar; then extend from
  the ledger and confirm the end time moved; then delete and confirm it disappears.
- **Settings:** exercise all three stop behaviors and confirm each renders the expected UI.
- **AppFunctions:** only with `-Pcalwrite.appfunctions=true`; confirm it compiles and the
  functions are indexed via adb. Not expected to be callable from Gemini.

## Phase 7 — Hardening and cleanup

Voice control is a large enough shift that the surrounding rot becomes load-bearing. These are
in scope, not deferred.

### The unit test source set does not compile

`app/src/test/java/.../LocalizationTest.kt` uses `RobolectricTestRunner` and
`androidx.test.core`'s `ApplicationProvider`, but neither is declared in `app/build.gradle.kts`
— `testImplementation` carries only `libs.junit`. `./gradlew testDebugUnitTest` fails during
`kaptDebugUnitTestKotlin` with `cannot find symbol: RobolectricTestRunner`. Main code compiles
clean; it is only the test source set that is broken.

Fix first, since everything below depends on being able to run tests:

- Add `robolectric` and `androidx.test:core` as `testImplementation`, via the version catalog
  (`gradle/libs.versions.toml`) to match the project's existing convention.
- Add `androidx.room:room-testing` for the migration test below.
- Confirm `./gradlew testDebugUnitTest` goes green before writing new tests.

### New test coverage

- **`VoiceActionHandler` name resolution** — the exact → normalized → prefix → substring ladder,
  and that genuine ambiguity yields `Ambiguous` rather than an arbitrary pick. This is the piece
  most likely to misbehave with real user-defined type names.
- **`extendLastEvent`** — extends a completed timed event; skips instant events; returns the
  previous `endTime`; no-ops when the candidate would not move forward.
- **Room migration 9 → 10** — `MigrationTestHelper` against the committed schema JSON, asserting
  existing rows survive and `calendarEventId` defaults to null.

### Dead and inert code

- Delete `data/database/CompletedEvent.kt` and `CompletedEventDao.kt`. They are not registered in
  `CalWriteDatabase` and have no accessor; the live record is the `events` table. Leaving a second
  plausible-looking "completed events" table next to a new ledger feature is a trap.
- Implement `EventRepository.getUnsyncedEvents()` for real — it currently returns `emptyList()`
  unconditionally (`EventRepository.kt:358`), which silently makes
  `CalendarRepository.retrySyncingUnsyncedEvents` (`:181`) a no-op. With `calendarEventId` landing
  in Phase 0 the correct query is finally expressible: completed events where `calendarEventId IS NULL`.

### Localization

The project maintains Spanish alongside English (`values-es/strings.xml`, 102 strings vs 103) and
documents the process in `docs/guides/LOCALIZATION.md`. Every new user-facing string — drawer confirmations,
query answers, disambiguation prompts, settings labels, ledger actions, error messages — ships in
both locales. Extend `LocalizationTest` to cover the new keys.

Note that voice result strings are assembled dynamically ("Stopped Exercise · 42m · saved"), so
they need plurals/format resources rather than concatenation, in both locales.

### Repository documentation

- Add a `README.md`. The repo has none — what the app is, how to build, how to run against an
  emulator, and where the roadmap lives.
- Move the ~20 root-level `*_FIX.md` / `*_IMPLEMENTATION.md` notes into `docs/notes/`, and the
  standing guides (localization, Photos setup, calendar colours, console verification) into
  `docs/guides/`, leaving the root to `README.md`, `ROADMAP.md`, `WARP.md`, and build files.
  Pure `git mv`, no content edits.

## Suggested sequencing

Phases 0–3 are a dependency chain and land the working feature. Phase 7's test-infrastructure fix
should come first in practice — it is small and everything else wants to be tested. Phases 4, 5,
and 6 barely touch each other and can proceed in parallel once Phase 1 exists. Phase 6 is
speculative and lands last, flag-off.

## Delivery

1. **Roadmap PR** (this document). The repo had no `README` and no roadmap doc — the root is a
   pile of ~20 ad-hoc `*.md` implementation notes. `ROADMAP.md` is introduced as the durable home
   for planned work, with this plan as its first entry.
2. **Then implement** on top of merged `main`, phase by phase. Phases 0–3 are a dependency chain
   and must land in order; Phases 4, 5, and 6 barely touch each other and can proceed in
   parallel once Phase 1 exists.

### Build/test environment (no physical device)

- SDK at `~/Android/Sdk`: platform `android-36`, build-tools `36.0.0`, emulator binary, and an
  `android-36 google_apis_playstore x86_64` system image are all present. JDK 21.
- **No AVD exists yet and `cmdline-tools` (hence `avdmanager`) is not installed** — create the
  AVD as a setup step, either by installing `cmdline-tools` into the existing SDK or by writing
  the `~/.android/avd/*.ini` + `config.ini` pair directly.
- `adb`/`emulator` are not on `PATH`; invoke them by absolute path under `~/Android/Sdk`.
- **The default `java` on this machine is JDK 21 without a compiler** (JRE-only package —
  `/usr/lib/jvm/java-21-openjdk-amd64` has no `bin/javac`), so Gradle fails with
  *"Toolchain installation … does not provide the required capabilities: [JAVA_COMPILER]"*.
  Build with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`, which is a full JDK.
- Gates before each PR: `./gradlew assembleDebug`, `./gradlew lintDebug`, `./gradlew testDebugUnitTest`,
  then `installDebug` on the emulator plus the `calwrite://` deep-link checks from Verification.
