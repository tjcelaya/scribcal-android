# Album Picker Automatic Retry and State Cleanup

## Problem Addressed

The album picker was failing due to stale app state from the previous auto-creation approach. When users had old/corrupted state, the "Change Album" button would show "Error" instead of the album picker dialog.

## Solution Implemented

### Automatic Retry with State Cleanup

The album picker now has intelligent retry logic that automatically detects failures and attempts to resolve them by clearing stale state:

1. **First Attempt**: Normal album picker flow
2. **On Failure**: Automatically clears all Photos state and retries once
3. **After Retry**: If it still fails, shows proper error to user

### Key Features

#### 1. **Comprehensive State Cleanup**
When retry is triggered, the system clears:
- OAuth tokens (via `photosRepository.clearCachedTokens()`)
- Album configurations from database
- In-memory state variables
- Persistent connection state

#### 2. **Multi-Level Retry Logic**
Retry is triggered at multiple failure points:
- **Photos Initialization Failure**
- **No Google Account Available**
- **OAuth Token Retrieval Failure**
- **Empty OAuth Token**
- **No Albums Found**
- **General Exceptions**

#### 3. **User Feedback**
Clear progress indication during retry:
- "Loading Albums..." (first attempt)
- "Clearing state and retrying..." (cleanup phase)
- "Reinitializing..." (reinit phase)

## Implementation Details

### New Method Structure

```kotlin
private fun showAlbumPicker() {
    lifecycleScope.launch {
        showAlbumPickerWithRetry(isRetry = false)
    }
}

private suspend fun showAlbumPickerWithRetry(isRetry: Boolean) {
    // Retry logic with state cleanup
}
```

### Retry Flow Logic

```kotlin
if (!initSuccess) {
    if (!isRetry) {
        Log.d("SettingsFragment", "Attempting retry with state cleanup...")
        showAlbumPickerWithRetry(isRetry = true)
        return
    }
    // Only show error after retry attempt
    handlePhotosConnectionError()
    return
}
```

### State Cleanup Process

```kotlin
if (isRetry) {
    Log.d("SettingsFragment", "Retry attempt: Clearing all Photos state...")
    photosRepository.clearCachedTokens()  // Clears OAuth tokens + database + prefs
    
    // Wait for cleanup to complete
    kotlinx.coroutines.delay(500)
    setPhotosButtonState(enabled = false, text = "Reinitializing...")
}
```

## Benefits

### 1. **Automatic Recovery**
- Users don't need to manually clear app data or reinstall
- Handles migration from old auto-creation approach seamlessly
- Resolves common OAuth and state corruption issues

### 2. **Better User Experience**
- Clear progress feedback during retry process
- Only shows final error after genuine attempt to fix issue
- Maintains button state properly throughout process

### 3. **Robust Error Handling**
- Catches failures at multiple levels
- Prevents infinite retry loops (max 1 retry attempt)
- Comprehensive logging for debugging

## Expected User Experience

### Scenario 1: First Use (Clean State)
1. User clicks "Choose Album"
2. Albums load successfully
3. User sees album picker dialog

### Scenario 2: Stale State (Auto-Recovery)
1. User clicks "Change Album" 
2. First attempt fails due to stale state
3. App shows "Clearing state and retrying..."
4. State is automatically cleaned
5. App shows "Reinitializing..."  
6. Second attempt succeeds
7. User sees album picker dialog

### Scenario 3: Genuine Error (After Retry)
1. User clicks "Change Album"
2. First attempt fails
3. Auto-retry with cleanup fails too
4. App shows "Error" with clear logging for debugging

## Logging Output

### Successful Retry Example:
```
SettingsFragment: Error showing album picker
SettingsFragment: Attempting retry with state cleanup...
SettingsFragment: Retry attempt: Clearing all Photos state...
PhotosRepository: Cleared cached OAuth token for Photos
PhotosRepository: Reset Photos connection state...
SettingsFragment: Photos already initialized
SettingsFragment: Getting OAuth token for account: user@gmail.com
SettingsFragment: OAuth token obtained successfully
SettingsFragment: Fetching albums from Google Photos...
SettingsFragment: Found 3 albums
SettingsFragment: Showing album selection dialog
```

## Files Modified

- **SettingsFragment.kt**: Added `showAlbumPickerWithRetry()` method with comprehensive retry logic
- **Existing**: `photosRepository.clearCachedTokens()` method handles all state cleanup

## Testing Recommendations

1. **Clean Install**: Verify normal flow works
2. **Upgrade Test**: Install old version, then upgrade to test automatic state migration
3. **Network Issues**: Test retry behavior with poor connectivity
4. **Account Issues**: Test behavior with revoked permissions

This implementation should resolve the "Error" issue you experienced by automatically cleaning up any stale state from the old auto-creation approach and presenting the album picker successfully on the retry attempt.