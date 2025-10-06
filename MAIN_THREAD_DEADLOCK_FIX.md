# Main Thread Deadlock Fix

## Problem

The album picker was crashing with this error:
```
java.lang.IllegalStateException: Calling this from your main thread can lead to deadlock
	at com.google.android.gms.auth.GoogleAuthUtil.getToken
```

## Root Cause

`GoogleAuthUtil.getToken()` is a blocking network call that cannot be executed on the main thread. Even though we were using `lifecycleScope.launch`, the coroutine was still running on the main dispatcher by default.

## Solution

Wrapped the `GoogleAuthUtil.getToken()` call with `withContext(Dispatchers.IO)` to explicitly run it on a background thread:

### Before (Deadlock):
```kotlin
val token = try {
    GoogleAuthUtil.getToken(
        requireContext(),
        account,
        "oauth2:https://www.googleapis.com/auth/photoslibrary"
    )
} catch (e: Exception) { ... }
```

### After (Fixed):
```kotlin
val token = try {
    withContext(Dispatchers.IO) {
        GoogleAuthUtil.getToken(
            requireContext(),
            account,
            "oauth2:https://www.googleapis.com/auth/photoslibrary"
        )
    }
} catch (e: Exception) { ... }
```

## Changes Made

1. **Added imports:**
   ```kotlin
   import kotlinx.coroutines.Dispatchers
   import kotlinx.coroutines.withContext
   ```

2. **Wrapped OAuth call:**
   - Used `withContext(Dispatchers.IO)` to switch to background thread
   - Preserves all existing error handling logic
   - Returns to main thread automatically after completion

## Why This Works

- `Dispatchers.IO` is optimized for network/disk operations
- `withContext()` suspends the coroutine and switches thread context
- After the block completes, execution returns to the original context (main thread)
- UI updates can continue safely on the main thread

## Verification

- ✅ Build successful
- ✅ App installs without errors
- ✅ No more main thread deadlock exception

The album picker should now work correctly without crashing when attempting to fetch the OAuth token.