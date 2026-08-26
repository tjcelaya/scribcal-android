# Google Cloud Console Verification Checklist

Based on the logs, we're getting "Request had insufficient authentication scopes" errors even though:
- OAuth tokens are being obtained successfully (322 characters, valid format)
- We're requesting the correct scope: `https://www.googleapis.com/auth/photoslibrary`
- Token clearing and refresh works properly

This indicates the OAuth client setup in Google Cloud Console is not properly configured to include the Photos Library API scope.

## Step-by-Step Verification

### 1. Google Cloud Console - APIs & Services

Go to: https://console.cloud.google.com/apis/dashboard

**Check API Enablement:**
- [ ] Google Photos Library API is **enabled** (not just visible, but enabled)
- [ ] Look for "Google Photos Library API" in the enabled APIs list
- [ ] If not enabled: Click "+ ENABLE APIS AND SERVICES", search for "Photos Library API", enable it

### 2. OAuth Consent Screen Configuration

Go to: https://console.cloud.google.com/apis/credentials/consent

**Verify Consent Screen Setup:**
- [ ] OAuth consent screen is configured (not in "needs setup" state)
- [ ] App name is filled in
- [ ] User support email is set
- [ ] Developer contact email is set
- [ ] **CRITICAL**: Check "Scopes" section - must include Photos Library API scope

**Scopes Section (MOST IMPORTANT):**
- [ ] Click "ADD OR REMOVE SCOPES"
- [ ] In the filter/search, type "Photos" or "photoslibrary"
- [ ] **MUST HAVE**: `https://www.googleapis.com/auth/photoslibrary` scope checked
- [ ] Description should say something like "See, upload, and organize items in your Google Photos library"
- [ ] Click "UPDATE" to save scope changes

### 3. OAuth Client Credentials

Go to: https://console.cloud.google.com/apis/credentials

**Find your Android OAuth Client:**
- [ ] Look for client type "Android" 
- [ ] Package name: `com.tjcelaya.calwrite`
- [ ] SHA-1 fingerprint matches your keystore

**Verify Client Configuration:**
- [ ] Client ID exists and is properly formatted
- [ ] Package name exactly matches: `com.tjcelaya.calwrite`
- [ ] SHA-1 certificate fingerprint is correct

### 4. SHA-1 Fingerprint Verification

**Get your debug keystore SHA-1:**
```bash
# For debug builds (default Android debug keystore)
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# Look for the SHA1 line, should look like:
# SHA1: A1:B2:C3:D4:E5:F6:... (40 hex characters separated by colons)
```

**Verify in Google Cloud Console:**
- [ ] The SHA-1 in your OAuth client matches exactly
- [ ] No extra spaces or formatting differences
- [ ] All 40 hex characters match

### 5. Test the Fix

After making changes:
1. **Wait 5-10 minutes** for changes to propagate
2. **Revoke app permissions** in your Google Account:
   - Go to https://myaccount.google.com/permissions
   - Find "CalWrite" (or your app name)
   - Click "Remove access"
3. **Clear app data** on your device:
   - Settings > Apps > CalWrite > Storage > Clear Data
4. **Reinstall the app** to get a fresh OAuth flow
5. **Test connection** - should now prompt for consent with Photos access

## Common Issues

### Issue 1: Scope Not Added to Consent Screen
**Symptom:** Getting tokens but 403 "insufficient scopes" errors
**Fix:** Add `https://www.googleapis.com/auth/photoslibrary` to OAuth consent screen scopes

### Issue 2: API Not Enabled
**Symptom:** 403 "API not enabled" errors
**Fix:** Enable Google Photos Library API in APIs & Services

### Issue 3: Wrong SHA-1 Fingerprint
**Symptom:** OAuth flow doesn't start or fails early
**Fix:** Update OAuth client with correct SHA-1 from your debug keystore

### Issue 4: Package Name Mismatch
**Symptom:** OAuth client not recognized
**Fix:** Ensure package name is exactly `com.tjcelaya.calwrite`

## Debugging Commands

To verify the issue, run these and check logs:

```bash
# Build and install
cd /home/tjcelaya/src/calwrite
./gradlew assembleDebug && ./gradlew installDebug

# Clear logs and start app
/home/tjcelaya/Android/Sdk/platform-tools/adb logcat -c
/home/tjcelaya/Android/Sdk/platform-tools/adb shell am start -n com.tjcelaya.calwrite/.MainActivity

# Test connection and check logs
/home/tjcelaya/Android/Sdk/platform-tools/adb logcat -d -s PhotosRepository -s SettingsFragment --format=threadtime | tail -50
```

Look for:
- `🔍 Token scope analysis:` - Shows what scopes the token actually has
- `❌ TOKEN SCOPE MISMATCH:` - Confirms scope is missing from token
- `🔗 API Endpoint:` and `📨 API availability test response code: 403` - Confirms API call failure

## Expected Success Logs

When working correctly, you should see:
```
🔍 Token scopes: https://www.googleapis.com/auth/photoslibrary openid profile email (has Photos: true)
✅ Token includes required Photos scope
📨 API availability test response code: 200
✅ Google Photos API is available and accessible
```

## Project Context

**App Package:** `com.tjcelaya.calwrite`
**Required Scope:** `https://www.googleapis.com/auth/photoslibrary`
**Target API:** Google Photos Library API v1
**OAuth Flow:** Android GoogleAuthUtil with UserRecoverableAuthException handling