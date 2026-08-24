# Rename ScribCal to CalWrite

> **Status:** planned — not yet implemented.
> **Tracked in:** [ROADMAP.md](../../ROADMAP.md)

## Context

Google Assistant essentially cannot say or match "ScribCal" reliably — it's an invented word
with no clean phonetic reading, which undermines the whole voice-control surface
([`docs/plans/voice-control.md`](voice-control.md)). The fix is a real rename, not a workaround.

The new name is **CalWrite**: barebones and utilitarian, and it reads as a sibling to
**CalScope** (`~/src/calscope`), which has taken over CalWrite's former responsibility of
long-range event counting (daily/weekly/etc. tallies). CalWrite sheds that responsibility here
too — see Phase 3.

This is a single-user app (Tomas only), which simplifies things that would otherwise be much
more careful for a shipped product:

- The GitHub repo itself gets renamed, not just the code inside it.
- The Android `applicationId` changes (`com.tjcelaya.scribcal` → `com.tjcelaya.calwrite`), which
  means Android treats CalWrite as a brand-new, unrelated app — no automatic data carryover, no
  in-place upgrade, and the old ScribCal install stays on the phone side-by-side until manually
  uninstalled. Because this is a one-time move for one person's one phone, the migration is a
  throwaway `adb` script run once by hand, not an in-app import flow that has to keep working.

## Phase 1 — Repo rename

- `gh repo rename` on `tjcelaya/scribcal-android` → `tjcelaya/calwrite-android`.
- Rename the local working copy directory `~/src/scribcal` → `~/src/calwrite` and repoint the
  `origin` remote (GitHub redirects the old URL, but the local remote should point at the
  canonical name).
- Any of Tomas's other repos/notes that link to `scribcal-android` by URL should be checked
  after (`~/src/docs` knowledgebase, in particular) — out of scope for this plan to fix, but
  worth a grep afterward.

## Phase 2 — Code and identity rename

Everything under `com/tjcelaya/scribcal/**` (main, test, androidTest, and the `appfunctions`
source set) moves to `com/tjcelaya/calwrite/**`, package declarations and imports updated
accordingly. Alongside that:

| What | From | To |
|---|---|---|
| `namespace` / `applicationId` (`app/build.gradle.kts`) | `com.tjcelaya.scribcal` | `com.tjcelaya.calwrite` |
| `rootProject.name` (`settings.gradle.kts`) | `scribcal` (or current) | `calwrite` |
| Application class | `ScribCalApplication` | `CalWriteApplication` |
| `app_name` / visible label strings | `ScribCal` | `CalWrite` |
| Room database file (`ScribCalDatabase.kt`) | `scribcal_database` | `calwrite_database` |
| SharedPreferences names | `scribcal_prefs`, `scribcal_storage_prefs` | `calwrite_prefs`, `calwrite_storage_prefs` |
| Voice deep-link scheme (`VoiceIntentParser.kt`) | `scribcal://` | `calwrite://` |
| Backup config file name (`ConfigBackupManager.kt`, `DriveRepository.kt`) | `scribcal_config.json` | `calwrite_config.json` |
| Photos album pref key (`PhotosRepository.kt`) | `scribcal_album_id` | `calwrite_album_id` |
| Photo filename prefix (`EventRepository.kt`) | `scribcal_<type>_<ts>.jpg` | `calwrite_<type>_<ts>.jpg` |
| FileProvider authority | `${applicationId}.fileprovider` | unchanged (already derives from `applicationId`) |
| Docs (`README.md`, `ROADMAP.md`, `docs/**`) | "ScribCal" prose | "CalWrite" prose |

Mechanical scope: ~121 files reference "scribcal" case-insensitively today (excluding
`build/` and worktrees). This is almost entirely find-and-replace plus a directory move, well
suited to running with subagents in parallel across the source sets, followed by one full
compile + lint pass to catch anything the text search missed (e.g. resource IDs that embedded
"scribcal", generated `R` references).

Nothing here changes behavior — this phase should produce a build that is functionally
identical to today's ScribCal, just renamed and installed as `com.tjcelaya.calwrite`.

## Phase 3 — Drop the daily/weekly counter responsibility

CalScope now owns long-range event counting. CalWrite currently computes and displays this
itself via `EventRepository.getEventCountSince()`, called from:

- `EventsViewModel.kt` (`dailyCount`, `weeklyCount`)
- `TrackingViewModel.kt` (`dailyCount`, `weeklyCount`)

and rendered as badges by `TrackingAdapters.kt` / `UnifiedEventAdapter.kt` /
`EventDisplayItem.kt`.

Remove:
- The `dailyCount` / `weeklyCount` fields from both view models' UI state and the
  `getEventCountSince()` calls that populate them.
- The badge rendering in the adapters, and the now-dead fields on `EventDisplayItem`.
- `EventRepository.getEventCountSince()` itself once nothing calls it.

Add retention pruning, since nothing needs long-range history anymore — the recent-events
ledger (`docs/plans/voice-control.md` Phase 5: undo/delete/extend/adjust) only ever operates on
recent rows. Concretely:

- A retention window constant (default **90 days** — long enough to cover the ledger's
  practical use, short enough to keep the `events` table small; open to adjustment once this
  plan is reviewed).
- A prune step — delete `events` rows older than the window, keyed off `endTime` (or `startTime`
  for still-open events, which are never pruned). Run it opportunistically (e.g. app start /
  `ScribCalApplication.onCreate()` → `CalWriteApplication.onCreate()`) rather than as a
  scheduled job; this is a personal app, not a service.
- Calendar events are untouched — pruning only removes CalWrite's local `events` rows, which
  exist for the ledger and sync bookkeeping. The calendar remains the durable record, same as
  today.

## Phase 4 — One-time data migration (throwaway script, not app code)

Because `applicationId` changes, this is a copy between two unrelated apps' data directories,
done once via `adb` on the debug build, after Phase 1–3 land and CalWrite has been installed
and launched at least once (so its data directory exists):

```bash
# 1. Pull ScribCal's Room DB off the device (requires a debuggable build; adb run-as)
adb exec-out run-as com.tjcelaya.scribcal \
  cat databases/scribcal_database > /tmp/scribcal_database
adb exec-out run-as com.tjcelaya.scribcal \
  cat databases/scribcal_database-wal > /tmp/scribcal_database-wal 2>/dev/null || true

# 2. Push it into CalWrite's data dir under the new file name
adb push /tmp/scribcal_database /data/local/tmp/calwrite_database
adb shell run-as com.tjcelaya.calwrite \
  cp /data/local/tmp/calwrite_database databases/calwrite_database

# 3. Relaunch CalWrite
adb shell am force-stop com.tjcelaya.calwrite
adb shell monkey -p com.tjcelaya.calwrite -c android.intent.category.LAUNCHER 1
```

Scope of what's migrated: the `events` and `event_types` tables (the Room DB) — that's the
data that matters (event history within the retention window, and the user's event type
catalog). `StoragePreferences` (view mode, voice stop-behavior, card style) and
`CalendarRepository`'s selected-calendar preference are small enough to just re-set by hand in
the new app rather than scripting a SharedPreferences copy.

After migration is confirmed (open CalWrite, check event types and recent events are present),
uninstall ScribCal from the phone.

This script is written once, run once, and does not need to be maintained — it is not part of
the app and should not ship as in-app code or a permanent CI-tested path.

## Sequencing

Phases 1–3 land together as one PR (the rename is not meaningfully separable from the counter
removal, since both touch the same files and Tomas wants neither left half-finished). Phase 4
runs by hand afterward, once, outside of any PR.
