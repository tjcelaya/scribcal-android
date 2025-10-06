# ScribCal Localization Guide

This document explains how to add and maintain translations for the ScribCal app.

## Current Languages

- **English (Default)**: `/app/src/main/res/values/strings.xml`
- **Spanish**: `/app/src/main/res/values-es/strings.xml`

## Adding New Translations

### 1. Create Language Directory

For a new language, create a new values directory using the ISO 639-1 language code:

```bash
mkdir app/src/main/res/values-[LANGUAGE_CODE]
```

Examples:
- French: `values-fr`
- German: `values-de`
- Portuguese: `values-pt`
- Chinese (Simplified): `values-zh-rCN`
- Japanese: `values-ja`

### 2. Copy Base strings.xml

Copy the English strings.xml file to your new language directory:

```bash
cp app/src/main/res/values/strings.xml app/src/main/res/values-[LANGUAGE_CODE]/strings.xml
```

### 3. Translate Strings

Open the new strings.xml file and translate all the text content while keeping:
- String names (the `name` attribute) unchanged
- XML structure intact
- Format placeholders like `%s`, `%d`, `%1$s` unchanged

Example:
```xml
<!-- English -->
<string name="record_new_event">Record New Event</string>

<!-- Spanish -->
<string name="record_new_event">Registrar Nuevo Evento</string>
```

## String Categories

The strings.xml file is organized into categories:

- **App Information**: App name, basic labels
- **Navigation Labels**: Fragment titles, menu items  
- **Add Event Screen**: Form labels, buttons, help text
- **Messages and Toasts**: Success/error messages
- **Tracking Screen**: Main interface text
- **Settings**: Configuration options
- **Generic**: Common words like OK, Cancel, Loading

## Format Strings

Some strings contain placeholders for dynamic content:

```xml
<string name="error_format">Error: %s</string>
<string name="ongoing_events_multiple">%d ongoing events</string>
<string name="stop_event_message">Stop this event?\n\nElapsed time: %1$s\nStarted at: %2$s\n\nThe event will be saved to your calendar.</string>
```

Important notes:
- `%s` = string placeholder
- `%d` = number placeholder  
- `%1$s`, `%2$s` = positional placeholders (keep the numbers)
- `\n` = line break (keep unchanged)
- `formatted=\"false\"` attribute for strings with multiple placeholders

## Testing Translations

1. Build the app: `./gradlew assembleDebug`

2. Test on device by changing the device language in Settings

3. Run localization tests: `./gradlew testDebugUnitTest --tests "*LocalizationTest*"`

## Adding New Strings

When adding new translatable text to the app:

1. **Never hardcode text** in layouts or code
2. Add the string to `/app/src/main/res/values/strings.xml` first
3. Add translations to all existing language files
4. Use `getString(R.string.your_string_name)` in Kotlin code
5. Use `@string/your_string_name` in XML layouts

Example workflow:

```xml
<!-- Add to values/strings.xml -->
<string name="new_feature_title">New Feature</string>

<!-- Add to values-es/strings.xml -->
<string name="new_feature_title">Nueva Característica</string>
```

```kotlin
// Use in Kotlin code
val title = getString(R.string.new_feature_title)
```

```xml
<!-- Use in XML layout -->
<TextView android:text="@string/new_feature_title" />
```

## Translation Guidelines

### General Rules
- Keep translations concise and natural
- Maintain the same tone as the English version
- Consider cultural context and local conventions
- Test translations in the actual UI to ensure they fit

### Technical Considerations
- Respect Android string formatting rules
- Keep accessibility in mind
- Consider text expansion (some languages need more space)
- Preserve technical terms where appropriate (e.g., "Google Drive")

### Quality Assurance
- Have translations reviewed by native speakers
- Test all translated screens in the app
- Verify that long translations don't break the UI layout
- Check that context is preserved (same meaning as English)

## Common Issues

1. **Missing translations**: App falls back to English for missing strings
2. **Format string errors**: Mismatched placeholders cause crashes
3. **Layout issues**: Long translations might not fit in UI elements
4. **Character encoding**: Use UTF-8 encoding for special characters

## Resources

- [Android Localization Guide](https://developer.android.com/guide/topics/resources/localization)
- [ISO 639-1 Language Codes](https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes)
- [Android String Resources](https://developer.android.com/guide/topics/resources/string-resource)