# WARP.md

This file provides guidance to WARP (warp.dev) when working with code in this repository.

## Project Overview

ScribbleCal is an Android stopwatch application that tracks recurring events and writes them directly to the user's calendar. It functions as a set of customizable stopwatches for different event types, with seamless calendar integration for data persistence.

### Core Functionality
- **Event Type Management**: Users can register new types of recurring events to track
- **Instantaneous Events**: Record zero-duration events (start and finish times are identical)
- **Timed Events**: Start/stop stopwatch functionality for events with duration
- **Calendar Integration**: All completed events are automatically written to the user's selected calendar
- **Calendar Selection**: Users choose their primary calendar for data storage on first launch or via settings

### Technical Stack
- **Build System**: Gradle with Kotlin DSL
- **Language**: Kotlin 2.0.21
- **Min SDK**: API 34, Target SDK: API 36
- **Architecture**: Single Activity with Navigation Component
- **UI Framework**: View Binding with Material Design Components
- **Package**: `com.tjcelaya.scribcal`

## Development Commands

### Building and Running
```bash
# Build the project
./gradlew build

# Build debug APK
./gradlew assembleDebug

# Install debug APK to connected device/emulator
./gradlew installDebug

# Build and run on device/emulator in one command
./gradlew installDebug && adb shell am start -n com.tjcelaya.scribcal/.MainActivity
```

### Testing
```bash
# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run tests for debug build specifically
./gradlew testDebugUnitTest
```

### Code Quality
```bash
# Clean build directory
./gradlew clean

# Lint check
./gradlew lint

# Generate lint report
./gradlew lintDebug
```

## Architecture Overview

The app follows a data-driven architecture with calendar integration at its core:

### Core User Actions
1. **Register New Event Type**: Create custom categories for tracking (e.g., "Exercise", "Work Session", "Meeting")
2. **Record Instantaneous Event**: Log zero-duration events with identical start/end times
3. **Start Timed Event**: Begin stopwatch for duration-based tracking
4. **Stop Ongoing Event**: Complete started events with confirmation dialog

### Key Components
- **MainActivity**: Single activity hosting navigation and managing app lifecycle
- **Event Management**: UI for creating and managing event types
- **Stopwatch Interface**: Active tracking UI with start/stop functionality
- **Calendar Integration**: Write completed events to user's selected calendar
- **Settings**: Calendar selection and app configuration

### Required Permissions & Integration
- **Calendar Read/Write**: `READ_CALENDAR` and `WRITE_CALENDAR` permissions
- **Calendar Provider**: Integration with Android's CalendarContract API
- **Persistent Storage**: Store event types, ongoing events, and user preferences

### Data Flow
1. **First Launch**: Prompt user to select primary calendar
2. **Event Creation**: Store event types locally (SQLite/Room database)
3. **Event Recording**: Track start/end times for ongoing events
4. **Calendar Sync**: Write completed events to selected calendar via CalendarContract
5. **State Management**: Persist ongoing event states across app lifecycle

### Key Files Structure (Planned)
```
app/
├── src/main/java/com/tjcelaya/scribcal/
│   ├── MainActivity.kt              # Main activity with navigation
│   ├── data/
│   │   ├── CalendarRepository.kt    # Calendar integration logic
│   │   ├── EventRepository.kt       # Event data management
│   │   └── database/                # Room database entities & DAOs
│   ├── ui/
│   │   ├── events/                  # Event type management UI
│   │   ├── tracking/                # Stopwatch and recording UI
│   │   └── settings/                # Calendar selection and preferences
│   └── utils/
│       └── CalendarUtils.kt         # Calendar permission & integration helpers
├── src/main/res/
│   ├── navigation/nav_graph.xml     # Navigation between main screens
│   └── layout/                      # UI layouts for fragments
└── build.gradle.kts                # Dependencies including Room, Calendar APIs
```

### Dependencies Management
- Uses Gradle Version Catalog (`gradle/libs.versions.toml`)
- Core libraries: AndroidX Core KTX, AppCompat, Material Components, Navigation
- Database: Room for local storage of event types and ongoing events
- Calendar: Android CalendarContract API for calendar integration
- Test libraries: JUnit, AndroidX Test (JUnit + Espresso)

## Development Notes

### Calendar Integration Requirements
- **Permissions**: Declare `READ_CALENDAR` and `WRITE_CALENDAR` permissions in AndroidManifest.xml
- **Runtime Permissions**: Request calendar permissions at runtime (API 23+)
- **Calendar Selection**: Use CalendarContract.Calendars to list available calendars
- **Event Creation**: Use CalendarContract.Events to write completed events
- **Data Mapping**: Convert app's event data to calendar event format (title, start/end times, description)

### Event State Management
- **Ongoing Events**: Persist active stopwatch sessions to survive app restarts
- **Event Types**: Store user-defined event categories with custom names and settings
- **Timestamps**: Handle precise timing for stopwatch functionality
- **Confirmation**: Implement confirmation dialogs for stopping ongoing events

### View Binding Pattern
All fragments follow the standard view binding pattern with proper cleanup:
```kotlin path=null start=null
private var _binding: FragmentBinding? = null
private val binding get() = _binding!!

override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
}
```

### Navigation Usage
Fragments use `findNavController().navigate()` for navigation between destinations defined in `nav_graph.xml`.

### Build Configuration
- Java/Kotlin compatibility: Version 11
- ProGuard: Disabled for release builds (can be enabled if needed)
- Minimum SDK 34 targets recent Android versions
- View Binding enabled project-wide

## Android Development Environment

When working with this project, ensure you have:
- Android Studio or command-line Android development tools
- Android SDK with API level 34+ 
- An Android device or emulator for testing
- Gradle wrapper is included (`gradlew`/`gradlew.bat`)

The project is configured as a standard Android application and should work with typical Android development workflows.
