# Google Calendar Color Integration

## Overview
ScribbleCal now supports matching Google Calendar's standard event colors, plus the ability to select custom colors. When events are synced to Google Calendar, they will appear with the selected color.

## Google Calendar Standard Colors
The following 11 colors match Google Calendar's predefined event colors:

1. **Lavender** (#A4BDFC) - ID 1
2. **Sage** (#7AE7BF) - ID 2
3. **Grape** (#DBADFF) - ID 3
4. **Flamingo** (#FF887C) - ID 4
5. **Banana** (#FBD75B) - ID 5
6. **Tangerine** (#FFB878) - ID 6
7. **Peacock** (#46D6DB) - ID 7
8. **Graphite** (#E1E1E1) - ID 8
9. **Blueberry** (#5484ED) - ID 9
10. **Basil** (#51B749) - ID 10
11. **Tomato** (#DC2127) - ID 11

## Custom Colors
Users can also select a custom hex color that doesn't match the Google Calendar standards. Custom colors use ID `-1` internally and store the hex value separately.

## Implementation Details

### Database Schema Changes
The `EventType` entity has been updated from:
```kotlin
val color: Int? = null  // Single hex color field
```

To:
```kotlin
val colorId: Int? = null        // Google Calendar color ID (1-11) or CUSTOM_COLOR_ID (-1)
val customColorHex: Int? = null // Custom hex color, only used when colorId == -1
```

### Key Files Modified

1. **GoogleCalendarColors.kt** (NEW)
   - Defines the 11 standard Google Calendar colors
   - Provides utility methods for color lookup and matching
   - Includes `CUSTOM_COLOR_ID = -1` constant

2. **EventType.kt**
   - Added `colorId` and `customColorHex` fields
   - Added `getDisplayColor()` helper to get the appropriate hex color
   - Added `hasCustomColor()` helper to check if using custom color

3. **CalendarUtils.kt**
   - Updated `insertEventToCalendar()` to accept `colorId` and `customColorHex`
   - Updated `updateCalendarEvent()` to accept `colorId` and `customColorHex`
   - Sets `CalendarContract.Events.EVENT_COLOR` field when syncing

4. **CalendarRepository.kt**
   - Passes `eventType.colorId` and `eventType.customColorHex` to CalendarUtils

5. **AddEditEventTypeFragment.kt**
   - Displays 11 Google Calendar color buttons
   - Includes a "+" button to select custom colors
   - Saves both `colorId` and `customColorHex` appropriately

6. **EventTypeAutocompleteAdapter.kt**
   - Uses `eventType.getDisplayColor()` to show the correct color

### Usage

#### In Event Type Creation/Editing:
- Users see 11 colored buttons matching Google Calendar colors
- A "+" button allows selecting a custom color
- Selected color is highlighted with a white stroke
- Color is saved to the event type

#### In Calendar Sync:
- When an event is synced to Google Calendar, the `EVENT_COLOR` field is set
- For standard colors (ID 1-11), the corresponding hex value is used
- For custom colors (ID -1), the `customColorHex` value is used directly
- If no color is set, Google Calendar uses the calendar's default color

### Database Migration
The database version has been bumped from 5 to 6 to accommodate the schema changes. Since `.fallbackToDestructiveMigration()` is enabled, existing data will be cleared during development.

## Future Enhancements

### Custom Color Picker
The current implementation has a placeholder custom color picker dialog. For production, consider:
- Integrating a color picker library (e.g., ColorPickerView, Colorful, or Material Color Picker)
- Adding a hex color input field
- Showing a color preview before confirming
- Providing recent/favorite custom colors

### Color Matching
The `GoogleCalendarColors.findClosestColor()` method can be used to:
- Migrate existing colors from the old `color` field
- Suggest the closest Google Calendar color when picking custom colors
- Provide color accessibility features

## Testing Checklist
- [ ] Create event type with Google Calendar color
- [ ] Create event type with custom color
- [ ] Edit event type and change color
- [ ] Sync event to Google Calendar and verify color appears
- [ ] Verify color appears in event type autocomplete
- [ ] Test with different Android versions and calendar providers
- [ ] Verify color persists across app restarts
