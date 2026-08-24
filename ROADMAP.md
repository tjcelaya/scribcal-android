# ScribCal Roadmap

ScribCal is an Android stopwatch that writes recurring events straight to your Google Calendar.
This file is the durable home for planned work: what's coming, what shape it takes, and why.

Detailed designs live in [`docs/plans/`](docs/plans/). One file per initiative, linked from the
table below. A plan lands here *before* the code does, so the design can be reviewed on its own.

> Note: the repository root also contains a number of older `*_FIX.md` / `*_IMPLEMENTATION.md`
> notes written after the fact. Those are historical records of work already shipped, not plans.
> New forward-looking work goes in `docs/plans/` and is indexed here.

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

## Known cleanups

Not scheduled, but worth recording:

- `data/database/CompletedEvent.kt` and `CompletedEventDao.kt` are dead code. Neither is
  registered in `ScribCalDatabase`'s `entities` list and there is no `completedEventDao()`
  accessor. The live record of completed events is the `events` table (`Event` with
  `endTime != null`).
- `EventRepository.getUnsyncedEvents()` unconditionally returns an empty list, so the
  retry-sync path it feeds is inert.
- The root directory's ~20 loose `*.md` notes would read better collected under `docs/`.
