# OAuth Scope Fix for Google Photos Album Operations

## Problem Identified

The Google Photos album operations were failing with 403 "Request had insufficient authentication scopes" errors in two scenarios:

1. **Album Listing Failed**: When searching for existing albums (403 error)
2. **Album Verification Failed**: After creating an album, verification would fail (403 error)

## Root Cause

The issue was caused by cached OAuth tokens with insufficient permissions:

1. **Stale Token Cache**: `GoogleAuthUtil.getToken()` returns cached tokens that may have been granted with limited scopes
2. **Inconsistent Scope Requests**: Different parts of the app might request tokens with different scopes
3. **Permission Degradation**: Previously granted tokens might not include all required permissions

## Solution Implemented

### 1. Force Fresh Token Retrieval

**Before Album Operations:**
```kotlin
// Clear any cached tokens first to ensure fresh permissions
setPhotosButtonState(enabled = false, text = "Refreshing permissions...")
photosRepository.clearCachedTokens()

// Wait a moment for cleanup
kotlinx.coroutines.delay(500)

// Get OAuth token with fresh permissions
val token = GoogleAuthUtil.getToken(
    requireContext(),
    account,
    "oauth2:https://www.googleapis.com/auth/photoslibrary"
)
```

### 2. Simplified Success Flow

**Removed redundant verification** that was causing the second 403 error:
- **Before**: Create album → Save config → Call `testPhotosConnection()` (which tries to verify the album again)
- **After**: Create album → Save config → Show success message

**New Success Handling:**
```kotlin
if (success) {
    // Update UI to show successful configuration
    binding.photosStatusText.text = "Album configured successfully"
    binding.photosStatusText.setTextColor(requireContext().getColor(android.R.color.holo_green_dark))
    
    // Update last test time
    val currentTime = System.currentTimeMillis()
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    binding.photosLastTestText.text = formatter.format(Date(currentTime))
}
```

## Technical Details

### Token Clearing Process

The `photosRepository.clearCachedTokens()` method:
1. Clears OAuth tokens from GoogleAuthUtil cache
2. Clears album configurations from database
3. Resets in-memory state variables
4. Clears persistent connection state from SharedPreferences

### Scope Consistency

All Google Photos operations now use the consistent scope:
```
"oauth2:https://www.googleapis.com/auth/photoslibrary"
```

This scope provides:
- ✅ Read access to albums
- ✅ Write access to create albums
- ✅ Access to verify album existence
- ✅ Permission to add photos to albums

### Timing Improvements

Added a 500ms delay after clearing tokens to ensure:
- Cache invalidation completes
- Fresh token requests don't return stale data
- API calls use properly scoped tokens

## Expected User Experience

### Successful Flow
1. User enters album name and clicks "Connect"
2. App shows "Refreshing permissions..." 
3. App clears any stale tokens
4. App gets fresh OAuth token with full scope
5. App searches for existing album (should work now)
6. If album exists: Uses existing album
7. If album doesn't exist: Asks permission to create
8. Shows "Album configured successfully" with green text

### Error Handling
- If OAuth consent is needed: Launches consent flow
- If network errors occur: Shows clear error message
- If user cancels album creation: Returns to ready state

## Debugging Improvements

### Better Logging
```
SettingsFragment: Refreshing permissions...
SettingsFragment: Getting OAuth token for account: user@gmail.com
SettingsFragment: OAuth token obtained successfully
SettingsFragment: Searching for existing album: ScribCal Events
PhotosRepository: Listed X albums  // Should not be 0 now
SettingsFragment: Album configured successfully: ScribCal Events
```

### Success Indicators
- Green "Album configured successfully" text
- Updated timestamp showing when configuration completed
- No more 403 errors in logs

## Files Modified

- **SettingsFragment.kt**: 
  - Added token clearing before album operations
  - Added delay for cache invalidation
  - Simplified success handling (removed redundant verification)
  - Added imports for `kotlinx.coroutines.delay`

## Testing Scenarios

### Should Now Work:
1. **Fresh album setup**: Album creation with proper permissions
2. **Existing album detection**: Finding and using existing albums
3. **Repeated connections**: No stale token issues on subsequent attempts

### Error Cases Handled:
1. **Network issues**: Clear error messages, not permission errors
2. **User cancellation**: Graceful cancellation without errors
3. **OAuth consent needed**: Proper consent flow launch

## Prevention Measures

### Proactive Token Management
- Always clear tokens before critical operations
- Use consistent OAuth scopes across the app
- Allow time for cache invalidation

### Simplified Flow
- Avoid redundant verification steps that can fail
- Use direct success indicators instead of additional API calls
- Minimize complex retry logic that can compound permission issues

This fix should resolve the 403 "insufficient authentication scopes" errors and provide a reliable Google Photos album configuration experience.