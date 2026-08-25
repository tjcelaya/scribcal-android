# CalWrite

An Android stopwatch that writes what you track straight to your Google Calendar.

You define the things you do repeatedly — Exercise, Coffee, Reading — and CalWrite gives each one
a button. Tap to record a moment, or start a stopwatch and stop it when you're done. Either way
the result lands on the calendar you chose, so your history lives somewhere you already look
instead of inside another app you have to remember to open.

- **Event types** — user-defined categories, each with its own colour and cadence.
- **Instant events** — zero-duration, for things you want to note but not time.
- **Timed events** — start/stop stopwatch, surviving app restarts via a persisted ongoing state.
- **Future events** — scheduled entries that fire when their time arrives.
- **Calendar integration** — completed events are written through `CalendarContract` to the
  calendar you select on first launch.
- **Photos** — optionally attach a photo, uploaded to Google Drive or Google Photos and linked
  from the calendar entry.

## Building

Requires the Android SDK (platform 36) and **a JDK that includes a compiler**. Some distributions
ship a JRE-only `java-21` package, which makes Gradle fail with *"Toolchain installation … does
not provide the required capabilities: [JAVA_COMPILER]"* — point `JAVA_HOME` at a full JDK:

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

./gradlew assembleDebug        # build
./gradlew installDebug         # install to a connected device or running emulator
./gradlew testDebugUnitTest    # unit tests (Robolectric)
./gradlew lintDebug            # lint
```

`local.properties` is not checked in; it needs `sdk.dir` pointing at your Android SDK. Android
Studio writes it for you on first open.

Unit tests run under Robolectric against real resources. `app/src/test/resources/robolectric.properties`
pins the SDK level, because Robolectric has no image for the project's `targetSdk` yet and
defaults to it when unpinned.

### Running the app

```bash
./gradlew installDebug && adb shell am start -n com.tjcelaya.calwrite/.MainActivity
```

## Layout

```
app/src/main/java/com/tjcelaya/calwrite/
├── data/                 # repositories: calendar, events, Drive, Photos, preferences
│   └── database/         # Room entities and DAOs
├── ui/                   # fragments, adapters, dialogs, notifications
├── utils/                # CalendarContract and colour helpers
└── CalWriteApplication.kt

docs/
├── plans/                # designs for planned work, indexed by ROADMAP.md
├── guides/               # standing guides: localization, Photos setup, calendar colours
└── notes/                # historical implementation notes, written after the fact
```

## Where things are documented

- **[ROADMAP.md](ROADMAP.md)** — what's planned and why. Designs land in `docs/plans/` before the
  code does.
- **[docs/guides/LOCALIZATION.md](docs/guides/LOCALIZATION.md)** — the app ships English and
  Spanish; every user-facing string belongs in both.
- **[WARP.md](WARP.md)** — architectural overview and conventions.
- **`docs/notes/`** — records of past work. Historical, not authoritative about current state.

## Schema changes

Room schemas are exported to `app/schemas/` and committed. When changing the database: bump
`version` in `CalWriteDatabase`, add a `Migration` and register it in `ALL_MIGRATIONS`, build to
generate the new JSON, commit that JSON, and add a migration test. The recipe is written out in a
comment at the top of `CalWriteDatabase`'s companion object.
