# Album Picker Debug Fixes

## Issue
The "Change Album" button was switching the status to "Error" instead of showing the album picker dialog.

## Root Cause Analysis
Several potential issues were identified in the original implementation:

1. **Button Text Reset**: The `testPhotosConnection()` method was resetting button text to "Connect" instead of preserving "Change Album"
2. **Async Context Issues**: The `finally` block in `showAlbumPicker()` was calling `getSelectedAlbum()` (suspend function) outside of a coroutine scope
3. **Nested Lifecycles**: `showAlbumSelectionDialog()` had unnecessary nested `lifecycleScope.launch` calls
4. **Insufficient Error Logging**: Limited logging made it difficult to identify where the failure was occurring

## Fixes Applied

### 1. Fixed Button State Management
**Before:**
```kotlin
} finally {
    setPhotosButtonState(enabled = true, text = "Connect")
    updateStorageSelectionStatus()
}
```

**After:**
```kotlin
} finally {
    // Restore appropriate button text based on album selection
    val selectedAlbum = photosRepository.getSelectedAlbum()
    val buttonText = if (selectedAlbum != null) "Change Album" else "Choose Album"
    setPhotosButtonState(enabled = true, text = buttonText)
    updateStorageSelectionStatus()
}
```

### 2. Fixed Async Context in Finally Block
**Before:**
```kotlin
} finally {
    // Re-enable button
    val selectedAlbum = photosRepository.getSelectedAlbum() // ❌ Suspend function call outside coroutine
    val buttonText = if (selectedAlbum != null) "Change Album" else "Choose Album"
    setPhotosButtonState(enabled = true, text = buttonText)
}
```

**After:**
```kotlin
} finally {
    // Re-enable button with appropriate text
    lifecycleScope.launch {
        val selectedAlbum = photosRepository.getSelectedAlbum() // ✅ Proper coroutine context
        val buttonText = if (selectedAlbum != null) "Change Album" else "Choose Album"
        setPhotosButtonState(enabled = true, text = buttonText)
    }
}
```

### 3. Simplified Album Selection Dialog
**Before:**
```kotlin
private fun showAlbumSelectionDialog(albums: List<PhotosRepository.AlbumInfo>) {
    // ...
    lifecycleScope.launch {
        // Get selected album
        // Show dialog with nested lifecycleScope.launch in click handler
    }
}
```

**After:**
```kotlin
private suspend fun showAlbumSelectionDialog(albums: List<PhotosRepository.AlbumInfo>) {
    // Get selected album directly (already in suspend context)
    val selectedAlbum = photosRepository.getSelectedAlbum()
    // Show dialog with single lifecycleScope.launch in click handler
}
```

### 4. Enhanced Error Logging
Added comprehensive logging at each step:

```kotlin
// Photos initialization
Log.d("SettingsFragment", "Photos not initialized, attempting to initialize...")
Log.d("SettingsFragment", "Initializing Photos with account: ${account.name}")

// OAuth token retrieval  
Log.d("SettingsFragment", "Getting OAuth token for account: ${account.name}")
Log.w("SettingsFragment", "User consent required for Photos access")

// Album fetching
Log.d("SettingsFragment", "Fetching albums from Google Photos...")
Log.d("SettingsFragment", "Found ${albums.size} albums")

// Error cases
Log.e("SettingsFragment", "No Google account available for OAuth token")
Log.e("SettingsFragment", "OAuth token is empty")
```

### 5. Better Error Handling
Added proper error handling with fallbacks:

```kotlin
} else {
    Log.e("SettingsFragment", "Failed to set selected album")
    handlePhotosConnectionError() // ✅ Show error state
}
```

## Debugging Steps for Testing

### When testing the fixed version:

1. **Check Logs**: Look for the detailed log messages to identify exactly where the failure occurs:
   ```bash
   adb logcat | grep "SettingsFragment"
   ```

2. **Common Failure Points**:
   - **No Google Account**: Check if calendar setup is complete
   - **OAuth Token Issues**: May need user consent
   - **API Errors**: Google Photos API might be returning errors
   - **Network Issues**: Check internet connectivity

3. **Expected Log Flow** (successful case):
   ```
   SettingsFragment: Photos already initialized
   SettingsFragment: Getting OAuth token for account: user@gmail.com  
   SettingsFragment: OAuth token obtained successfully
   SettingsFragment: Fetching albums from Google Photos...
   SettingsFragment: Found 5 albums
   SettingsFragment: Showing album selection dialog
   ```

4. **Expected Log Flow** (error case will show where it fails):
   ```
   SettingsFragment: Photos not initialized, attempting to initialize...
   SettingsFragment: No Google account available for Photos initialization
   SettingsFragment: Error showing album picker
   ```

## Next Steps for Testing

1. Build and install the app
2. Go to Settings → Google Photos section
3. Click "Change Album" button
4. Check device logs to see the detailed flow
5. If it still shows "Error", the logs will now clearly indicate the failure point

The enhanced logging will make it much easier to identify whether the issue is:
- Account/authentication problems
- OAuth consent requirements  
- API connectivity issues
- Album fetching problems
- UI state management issues

## Files Modified

- `/app/src/main/java/com/tjcelaya/calwrite/ui/settings/SettingsFragment.kt` - Fixed async contexts, button state management, and added comprehensive logging