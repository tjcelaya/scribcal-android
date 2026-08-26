# Google Photos Album Picker Implementation

## Overview

This document describes the implementation of a Google Photos album picker to replace the previous auto-creation approach that was causing duplicate album issues. Instead of automatically creating a "CalWrite Events" album, users now select from their existing Google Photos albums.

## Changes Made

### 1. PhotosRepository Updates

#### New Methods Added:
- **`AlbumInfo` data class**: Holds album information (ID, title, photo count)
- **`listAlbums(accessToken: String)`**: Fetches all user's Google Photos albums via REST API
- **`setSelectedAlbum(albumId: String, albumTitle: String)`**: Stores user's selected album in database
- **`getSelectedAlbum()`**: Retrieves currently selected album from database

#### Modified Methods:
- **`testPhotosConnection()`**: Now verifies selected album exists instead of auto-creating new ones
- **`findOrCreateCalWriteAlbum()`**: Marked as deprecated in favor of user selection approach

### 2. SettingsFragment Updates

#### New UI Behavior:
- **Album Picker Dialog**: Shows list of user's existing Google Photos albums
- **Dynamic Button Text**: Button changes from "Choose Album" to "Change Album" based on selection state
- **Album Display**: Shows selected album name instead of hardcoded "CalWrite Events"

#### New Methods:
- **`showAlbumPicker()`**: Main method that handles album picker flow
- **`showAlbumSelectionDialog(albums: List<AlbumInfo>)`**: Displays selection dialog
- **`updatePhotosAlbumInfo()`**: Updates UI to show current album selection

### 3. UI Layout Updates
- Changed label from "Album Name" to "Selected Album" for clarity

## User Experience Flow

### First Time Setup:
1. User clicks "Choose Album" button
2. App requests Google Photos permissions if needed
3. System fetches all user's albums from Google Photos
4. Dialog shows album list with photo counts
5. User selects desired album
6. App verifies album accessibility and updates UI

### Subsequent Usage:
1. UI shows currently selected album name
2. "Change Album" button allows switching to different album
3. Connection test verifies selected album still exists
4. If album was deleted externally, user gets clear error message to select new one

## Technical Benefits

### Problem Solved:
- **No More Duplicate Albums**: Users select from existing albums instead of auto-creating
- **User Control**: Users choose which album to use for CalWrite photos
- **Better Error Handling**: Clear feedback when selected album no longer exists

### Implementation Details:
- Uses Google Photos Library REST API for album listing
- Database stores selected album configuration with verification timestamps
- Graceful handling of OAuth consent requirements
- Proper error states for missing albums or connection issues

## API Integration

### Google Photos REST API Calls:
```
GET https://photoslibrary.googleapis.com/v1/albums?pageSize=50
```
- Supports pagination for users with many albums
- Returns album ID, title, and media item count
- Requires `photoslibrary` OAuth scope

### Database Schema:
```kotlin
@Entity(tableName = "album_config")
data class AlbumConfig(
    @PrimaryKey val id: Int,
    val googlePhotosAlbumId: String,
    val googlePhotosAlbumName: String,
    val createdAt: Long,
    val lastVerified: Long
)
```

## Error Handling

### No Albums Found:
- Shows dialog explaining user needs to create albums in Google Photos first

### Selected Album Deleted:
- Connection test detects missing album
- Clear error message prompts user to select different album

### OAuth Issues:
- Automatically launches consent flow when needed
- Graceful fallback if permissions are denied

## Future Enhancements

### Potential Improvements:
1. **Album Creation**: Option to create new album from within CalWrite
2. **Album Preview**: Show album thumbnails in selection dialog  
3. **Batch Operations**: Allow multiple album selection for different event types
4. **Smart Suggestions**: Prioritize albums with CalWrite-related names

## Migration Notes

### Backward Compatibility:
- Existing auto-created "CalWrite Events" albums remain functional
- Users with existing album configurations can switch to new albums seamlessly
- Deprecated methods remain for reference but won't be used in new flows

### Testing Verification:
- Successfully builds and installs on Android devices
- Album picker dialog functions properly
- Selected albums are stored and retrieved correctly
- Connection tests verify album accessibility

This implementation provides a much more intuitive and error-resistant approach to Google Photos integration, giving users full control over their photo organization while eliminating the duplicate album creation issue.