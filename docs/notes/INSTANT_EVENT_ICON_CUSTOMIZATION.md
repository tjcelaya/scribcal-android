# Instant Event Icon Customization

## Overview
Added a new feature that allows users to customize the icon used for recording instant events throughout the app.

## Icon Options Available

Users can choose from 7 different icon styles:

1. **Pencil (Default)** - `ic_edit` - The original edit/pencil icon
2. **Checkmark** - `ic_check` - Represents marking something as done
3. **Plus** - `ic_add` - Simple addition symbol
4. **Calendar with Check** - `ic_event_available` - Calendar with checkmark overlay
5. **Bookmark** - `ic_bookmark` - Bookmark/flag symbol
6. **Circle** - `ic_circle` - Simple filled circle
7. **Note** - `ic_note_add` - Document with plus symbol

## Where to Change the Icon

1. Open the app and navigate to **Settings** (via the menu)
2. Scroll down to the **"Instant Event Icon"** section
3. Tap the dropdown menu
4. Select your preferred icon style
5. The change takes effect immediately

## Where the Icon Appears

The selected icon will appear in two places:

1. **Event Type List** - The small button next to each event type that records an instant event
2. **Future locations** - Any new UI elements that allow instant event recording

## Technical Details

### Files Modified

- **StoragePreferences.kt** - Added `InstantEventIcon` enum and preference storage
- **SettingsFragment.kt/.xml** - Added UI for icon selection
- **TrackingAdapters.kt** - Updated adapter to use dynamic icon
- **item_event_type_tracking.xml** - Removed hardcoded icon reference

### New Icon Resources Created

- `ic_check.xml`
- `ic_event_available.xml`
- `ic_bookmark.xml`
- `ic_circle.xml`
- `ic_note_add.xml`

### Preference Storage

The selected icon is stored in SharedPreferences under the key `instant_event_icon` and persists across app restarts.

## Future Enhancements

Potential future improvements:
- Add icon previews in the selection dropdown
- Allow custom icons from user's device
- Different icons for different event types
- Icon size customization
