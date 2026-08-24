# ScribCal Roadmap

ScribCal is an Android stopwatch that writes recurring events straight to your Google Calendar.
This file is the durable home for planned work: what's coming, what shape it takes, and why.

Detailed designs live in [`docs/plans/`](docs/plans/). One file per initiative, linked from the
table below. A plan lands here *before* the code does, so the design can be reviewed on its own.

Other documentation: [`docs/guides/`](docs/guides/) holds standing guides (localization, Photos
setup, calendar colours), and [`docs/notes/`](docs/notes/) holds implementation notes written
after the fact — historical records of shipped work, not plans. See also [README.md](README.md).

## Planned

| Initiative | Plan | Status |
|---|---|---|
| Voice control via Google Assistant & Gemini, plus a recent-events ledger | [`docs/plans/voice-control.md`](docs/plans/voice-control.md) | Planned |

### Voice control (Assistant + Gemini)

Drive the core actions — start, stop, record-instant, extend, and "what am I tracking?" — by
voice, from whichever assistant the user has set.

Google has split this surface in two, and the halves do not overlap:

- **Google Assistant** consumes App Actions declared in `res/xml/shortcuts.xml` (built-in intents
  plus dynamic shortcuts). Supported and working today.
- **Gemini** ignores Assistant shortcuts entirely. Its replacement is **AppFunctions**
  (`androidx.appfunctions`), currently in private preview with trusted testers; Gemini cannot yet
  invoke third-party AppFunctions outside adb testing.

The plan builds the Assistant path for real and the AppFunctions path behind a Gradle flag, with
both routed through one shared action layer so the next surface is a thin adapter.

It also carries two pieces of adjacent work the voice features depend on:

- **Persisting calendar event IDs.** `EventRepository.markEventAsSynced()` is currently a no-op
  stub and `Event` has no `calendarEventId` column, so calendar IDs are returned by the sync path
  and discarded. Nothing can reach back and modify a calendar entry. This blocks extend, undo, and
  adjust alike.
- **A recent-events ledger.** A new screen listing recent recordings newest-first, with undo,
  delete, extend-to-now, and time adjustment — each change propagating to the calendar copy.

## Hardening carried by the voice work

Voice control is a large enough shift that the surrounding rot becomes load-bearing, so these
are in scope alongside it rather than deferred. Detail in the plan's Phase 7.

- **The unit test source set does not compile.** `LocalizationTest.kt` uses Robolectric and
  `androidx.test.core`, neither declared in `app/build.gradle.kts`; `./gradlew testDebugUnitTest`
  fails at kapt with `cannot find symbol: RobolectricTestRunner`. Main code is fine.
- **Dead code:** `data/database/CompletedEvent.kt` and `CompletedEventDao.kt` are not registered
  in `ScribCalDatabase` and have no accessor. The live record of completed events is the `events`
  table. Removed, so they aren't mistaken for the new ledger's backing store.
- **Inert code:** `EventRepository.getUnsyncedEvents()` unconditionally returns an empty list,
  silently making `CalendarRepository.retrySyncingUnsyncedEvents` a no-op.
- **No README**, and the root directory carries ~20 loose implementation notes that belong under
  `docs/notes/`.

## Building

The app targets `compileSdk` 36 and builds with Gradle. Note that a JDK **with a compiler** is
required — some distributions ship a JRE-only `java-21` package, which makes Gradle fail with
*"Toolchain installation … does not provide the required capabilities: [JAVA_COMPILER]"*:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew assembleDebug
```
