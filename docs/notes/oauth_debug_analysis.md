# OAuth Debugging Analysis

## Current Status
You've exhausted all standard OAuth consent screen fixes:
- ✅ Added Photos Library scope to consent screen
- ✅ Removed and re-added the scope  
- ✅ Tried different Google Cloud projects
- ✅ Tried different Google accounts
- ✅ Cleared app storage multiple times
- ✅ Revoked and re-granted permissions

Yet you're still getting "Request had insufficient authentication scopes" despite obtaining OAuth tokens successfully.

## Advanced Debugging Theories

### Theory 1: Android GoogleAuthUtil API Bug
**Issue**: Android's `GoogleAuthUtil.getToken()` has known issues with certain OAuth scope formats
**Test**: The updated app will test 5 different scope request formats
**What to look for**: One format works while others fail

### Theory 2: Google Play Services Version Issue  
**Issue**: Play Services Auth 21.2.0 may have OAuth bugs
**Current**: `com.google.android.gms:play-services-auth:21.2.0`
**Alternative**: Try downgrading to 20.7.0 or upgrading to 21.3.0

### Theory 3: Library Dependency Conflict
**Issue**: Google Photos Library Client might interfere with OAuth scopes
**Current**: `google-photos-library-client:1.7.3`
**Test**: Temporarily remove this dependency and test OAuth

### Theory 4: OAuth Client Configuration Issue
**Issue**: Despite multiple projects, there might be a subtle configuration issue
**Debug**: Check exact client configuration format

### Theory 5: Google Account OAuth State Issue
**Issue**: Your Google account might have corrupted OAuth state
**Test**: Try with a completely different Google account on device

## Debug Build Instructions

When your device is connected, we'll run this debugging sequence:

```bash
# 1. Build with comprehensive debugging
cd /home/tjcelaya/src/scribcal
./gradlew assembleDebug
/home/tjcelaya/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# 2. Clear logs and start fresh
/home/tjcelaya/Android/Sdk/platform-tools/adb logcat -c
/home/tjcelaya/Android/Sdk/platform-tools/adb shell am start -n com.tjcelaya.scribcal/.MainActivity

# 3. Test and capture detailed logs
/home/tjcelaya/Android/Sdk/platform-tools/adb logcat -d -s PhotosRepository -s SettingsFragment --format=threadtime > oauth_debug.log
```

## What the Debug Build Will Show

### 🧪 Scope Testing Results
```
✅ Scope #1 'oauth2:https://www.googleapis.com/auth/photoslibrary': SUCCESS (token length 322)
   → Token scopes: openid profile email (has Photos: false)
❌ Scope #2 'oauth2:photoslibrary': UserRecoverableAuthException: NeedPermission
✅ Scope #3 'https://www.googleapis.com/auth/photoslibrary': SUCCESS (token length 322)
   → Token scopes: https://www.googleapis.com/auth/photoslibrary openid profile email (has Photos: true)
```

### 🔍 Token Analysis
```
🔍 Debugging token scopes...
🔍 Token info response: {"scope":"openid profile email","audience":"..."}
❌ TOKEN SCOPE MISMATCH: Required scope 'https://www.googleapis.com/auth/photoslibrary' not found in token
❌ Available scopes: openid profile email
```

## Expected Findings

Based on your thorough testing, we should see one of these patterns:

### Pattern A: Scope Format Issue
- One scope format includes Photos scope, others don't
- **Fix**: Use the working format consistently

### Pattern B: Complete Scope Absence  
- No scope format includes Photos scope despite consent screen configuration
- **Indicates**: Play Services or library dependency issue

### Pattern C: Token Says It Has Scope But API Rejects It
- Token includes Photos scope but API still returns 403
- **Indicates**: OAuth client configuration issue or Google API bug

## Alternative Approaches to Try

### 1. Dependency Version Changes

```gradle
// Try older Play Services version
implementation("com.google.android.gms:play-services-auth:20.7.0")

// OR try newer version
implementation("com.google.android.gms:play-services-auth:21.3.0")
```

### 2. Remove Google Photos Client Library

```gradle
// Comment out this dependency temporarily
// implementation("com.google.photos.library:google-photos-library-client:1.7.3")
```

### 3. Direct REST API Approach

Instead of using AndroidGoogleAuthUtil, try the standard OAuth 2.0 flow:
- Launch browser-based OAuth flow
- Handle redirect with authorization code
- Exchange code for access token with explicit scope request

### 4. Alternative Google Account

Test with a Google account that:
- Has never used this app
- Is in a different Google Workspace (if applicable)
- Has different country/region settings

## Manual Verification Steps

### 1. Check Token Manually
Copy a token from logs and check it manually:
```bash
curl "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=YOUR_TOKEN"
```

### 2. Test API Call Manually
```bash
curl -H "Authorization: Bearer YOUR_TOKEN" \
     "https://photoslibrary.googleapis.com/v1/albums?pageSize=1"
```

### 3. Verify OAuth Client in Cloud Console
- Package name: exactly `com.tjcelaya.scribcal`
- SHA-1: matches debug keystore exactly
- No extra spaces or characters

## Debug Session Plan

1. **Connect device** and install debug build
2. **Run comprehensive scope testing** (5 different formats)
3. **Analyze token contents** with Google's tokeninfo API
4. **Try dependency changes** based on results
5. **Test alternative OAuth approaches** if needed

This should definitively identify whether the issue is:
- ❓ Android API bug
- ❓ Dependency conflict  
- ❓ OAuth client misconfiguration
- ❓ Google account OAuth state issue
- ❓ Google API service issue

Once we identify the root cause, we can apply the appropriate fix.