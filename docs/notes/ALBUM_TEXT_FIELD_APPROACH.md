# Album Name Text Field Approach

## Overview

Replaced the complex album picker approach with a simple text field where users can specify their desired Google Photos album name. The connection test now handles finding or creating the album automatically with user confirmation.

## Key Changes

### 1. UI Changes
- **Replaced album display** with an editable text field (`TextInputEditText`)
- **Default value**: "ScribCal Events"
- **Material Design 3** styling with outlined box
- **Input validation**: Prevents empty album names

### 2. New User Flow

#### **Album Name Input**
1. User sees text field with current album name (or default "ScribCal Events")
2. User can edit the album name as desired
3. User clicks "Connect" to test connection

#### **Connection Test with Album Management**
1. **Validate input**: Check album name is not empty
2. **Initialize Photos**: Set up Google Photos API access
3. **Get OAuth token**: Handle consent flow if needed
4. **Search for album**: Look for existing album with the specified name
5. **Create if needed**: Ask user permission to create album if it doesn't exist
6. **Save configuration**: Store album ID and name in database
7. **Test connection**: Verify everything works

### 3. Album Creation Flow

#### **Existing Album Found**
```
User enters "My Photos" → Search finds existing album → Use existing album → Success
```

#### **Album Doesn't Exist**
```
User enters "ScribCal Events" → Search finds no match → Show dialog:
"Album 'ScribCal Events' doesn't exist. Create it?" → User clicks "Create" → Create album → Success
```

#### **User Cancels Creation**
```
User enters "New Album" → Search finds no match → Show creation dialog → User clicks "Cancel" → Failure
```

## Technical Implementation

### 1. UI Layout Changes

**Before (Album Display):**
```xml
<TextView
    android:id="@+id/photos_album_text"
    android:text="Selected Album" />
```

**After (Editable Text Field):**
```xml
<com.google.android.material.textfield.TextInputLayout
    android:hint="Album Name">
    <com.google.android.material.textfield.TextInputEditText
        android:id="@+id/photos_album_name_edit"
        android:text="ScribCal Events" />
</com.google.android.material.textfield.TextInputLayout>
```

### 2. New Methods Added

#### **loadAlbumNameIntoTextField()**
- Loads current album name from database into text field
- Falls back to "ScribCal Events" if no album configured

#### **testPhotosConnectionWithAlbumCreation()**
- Main connection test method
- Handles OAuth, album search/creation, and configuration

#### **findOrCreateAlbumWithConfirmation()**
- Searches for album by name
- Prompts user for creation if not found

#### **findAlbumByName()**
- Searches through user's albums
- Case-insensitive matching

#### **askUserToCreateAlbum()**
- Shows confirmation dialog
- Uses suspendCancellableCoroutine for async dialog handling

#### **createAlbum()**
- Creates new album via Google Photos REST API
- Returns album ID on success

### 3. Removed Methods
- `showAlbumPicker()` - No longer needed
- `showAlbumPickerWithRetry()` - Complex retry logic removed
- `showAlbumSelectionDialog()` - Album selection dialog removed
- `updatePhotosAlbumInfo()` - Replaced with text field loading

## Benefits

### 1. **Performance Improvements**
- **No mass queries**: Doesn't fetch all user's albums
- **Targeted search**: Only searches for specific album name
- **Minimal API calls**: Only creates album when necessary

### 2. **Better User Experience**
- **Simple input**: Just type the desired album name
- **Clear feedback**: Shows exactly what's happening (searching, creating, etc.)
- **User control**: Explicit confirmation before creating albums
- **Intuitive**: Text field approach is more familiar than complex picker

### 3. **Reduced Complexity**
- **Fewer edge cases**: No need to handle empty album lists, pagination, etc.
- **Simpler error handling**: Focused on album creation/access issues
- **Less code**: Removed hundreds of lines of complex retry logic

## User Experience Examples

### Scenario 1: Existing Album
1. User types "Family Photos"
2. Clicks "Connect"
3. App finds existing "Family Photos" album
4. Success - ready to use

### Scenario 2: New Album Creation
1. User types "ScribCal Events"
2. Clicks "Connect" 
3. App shows: "Album 'ScribCal Events' doesn't exist. Create it?"
4. User clicks "Create"
5. App creates album and configures it
6. Success - ready to use

### Scenario 3: User Declines Creation
1. User types "Work Photos"
2. Clicks "Connect"
3. App shows creation dialog
4. User clicks "Cancel"
5. Connection fails gracefully

## Error Handling

### Input Validation
- Empty album name → Show error on text field
- Invalid characters → Google Photos API will handle

### API Errors
- OAuth issues → Launch consent flow
- Network errors → Clear error messages
- Album creation failure → Detailed logging

### User Cancellation
- Creation dialog cancelled → Return to ready state
- No punishment for changing mind

## Migration from Old Approach

### Existing Users
- Text field loads current album name from database
- Existing album configurations work unchanged
- No data loss or reconfiguration needed

### New Users
- Text field defaults to "ScribCal Events"
- First connection will create default album (with permission)
- Clean, straightforward setup experience

This approach eliminates the performance issues of querying all albums while providing a much more intuitive user interface. Users simply specify what they want, and the app handles the technical details of finding or creating it.