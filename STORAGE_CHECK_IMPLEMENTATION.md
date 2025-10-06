# Storage Configuration Check for Camera and Photo Buttons

## Overview

Added storage configuration validation to the camera and photo picker floating action buttons. The buttons now check if photo storage is properly configured before allowing users to take or select photos, preventing errors and providing clear guidance.

## Implementation Details

### 1. Modified Button Click Handlers

**Before:**
```kotlin
binding.cameraFab.setOnClickListener {
    openCamera()
}

binding.imageFab.setOnClickListener {
    openImagePicker()
}
```

**After:**
```kotlin
binding.cameraFab.setOnClickListener {
    checkStorageConfigurationThenOpenCamera()
}

binding.imageFab.setOnClickListener {
    checkStorageConfigurationThenOpenImagePicker()
}
```

### 2. New Methods Added

#### **checkStorageConfigurationThenOpenCamera()**
- Checks if storage is configured before opening camera
- If configured: Opens camera normally
- If not configured: Shows storage setup dialog

#### **checkStorageConfigurationThenOpenImagePicker()**
- Checks if storage is configured before opening image picker
- If configured: Opens image picker normally
- If not configured: Shows storage setup dialog

#### **isPhotoStorageConfigured()**
- Determines if photo storage is properly configured
- Checks selected storage type (Google Drive or Google Photos)
- Verifies the selected service is ready for operations

#### **showStorageConfigurationDialog()**
- Shows error dialog with clear message
- Provides "Go to Settings" button to fix the issue
- Allows cancellation if user changes their mind

### 3. Storage Configuration Logic

The `isPhotoStorageConfigured()` method checks:

1. **Storage Type Selected**: User has chosen Google Drive or Google Photos
2. **Service Ready**: The selected service has a healthy connection

```kotlin
private fun isPhotoStorageConfigured(): Boolean {
    val selectedStorageType = storagePreferences.getPhotoStorageType()
    
    return when (selectedStorageType) {
        StoragePreferences.STORAGE_TYPE_GOOGLE_DRIVE -> {
            driveRepository.isDriveReady()
        }
        StoragePreferences.STORAGE_TYPE_GOOGLE_PHOTOS -> {
            photosRepository.isPhotosReady()
        }
        else -> false // No storage type selected
    }
}
```

### 4. Error Dialog

When storage is not configured, users see:

**Dialog Title**: "Photo Storage Required"

**Dialog Message**: "Please set up photo storage first before taking or selecting photos."

**Actions**:
- **"Go to Settings"**: Navigates to settings fragment 
- **"Cancel"**: Dismisses dialog and returns to tracking view

## User Experience Flow

### Scenario 1: Storage Configured
1. User clicks camera/photo button
2. Storage check passes
3. Camera/image picker opens normally
4. User can take/select photos as usual

### Scenario 2: No Storage Configured
1. User clicks camera/photo button
2. Storage check fails (no service selected or service not ready)
3. Dialog appears: "Photo Storage Required"
4. User clicks "Go to Settings"
5. App navigates to Settings fragment
6. User can configure Google Drive or Google Photos
7. User returns and buttons work normally

### Scenario 3: User Cancels Setup
1. User clicks camera/photo button
2. Storage check fails
3. Dialog appears
4. User clicks "Cancel"
5. Returns to tracking view (no action taken)

## Error Prevention

This implementation prevents several error scenarios:

### 1. **No Storage Service Selected**
- **Before**: Camera/photo would work but photo saving would fail silently
- **After**: User prompted to select and configure storage first

### 2. **Service Not Ready**
- **Before**: Photos might fail to upload with unclear errors
- **After**: User guided to test and fix connection in settings

### 3. **Stale Configuration** 
- **Before**: Old cached data might cause upload failures
- **After**: Real-time check ensures service is actually working

## Technical Benefits

### 1. **Proactive Error Handling**
- Catches configuration issues before user takes photos
- Prevents frustration from failed photo saves
- Clear guidance on how to fix issues

### 2. **User-Friendly Navigation**
- Direct navigation to settings when needed
- No need to guess where to configure storage
- Seamless flow back to photo taking after setup

### 3. **Consistent Behavior**
- Both camera and image picker buttons behave identically
- Consistent error messages and resolution paths
- Unified storage validation logic

## Integration Points

### Repository Integration
- **DriveRepository**: `isDriveReady()` method for Google Drive status
- **PhotosRepository**: `isPhotosReady()` method for Google Photos status  
- **StoragePreferences**: `getPhotoStorageType()` for user's selection

### Navigation Integration
- Uses `findNavController().navigate(R.id.settingsFragment)` 
- Seamless navigation to settings for configuration
- Maintains navigation stack for proper back behavior

### UI Integration
- Uses Material Design `AlertDialog` for consistent styling
- Non-blocking dialogs that don't interrupt app flow
- Clear action buttons with obvious next steps

## Files Modified

- **TrackingFragment.kt**: Added storage validation and error dialog logic
- **Imports**: Added `StoragePreferences` import for storage type constants

## Testing Scenarios

1. **Fresh Install**: No storage configured → Dialog appears → Settings navigation works
2. **Drive Configured**: Google Drive working → Camera/photos work normally
3. **Photos Configured**: Google Photos working → Camera/photos work normally  
4. **Connection Lost**: Service configured but offline → Dialog appears with guidance
5. **Service Switched**: User changes from Drive to Photos → New service validated

This implementation ensures users have a smooth experience with photo functionality while providing clear guidance when configuration is needed.