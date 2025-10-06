package com.tjcelaya.scribcal.data

import android.content.Context
import android.content.SharedPreferences

class StoragePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "scribcal_storage_prefs"
        private const val KEY_GOOGLE_DRIVE_ENABLED = "google_drive_enabled"
        private const val KEY_GOOGLE_PHOTOS_ENABLED = "google_photos_enabled"

        // Legacy key for migration
        private const val KEY_PHOTO_STORAGE_TYPE = "photo_storage_type"
        const val STORAGE_TYPE_GOOGLE_DRIVE = "google_drive"
        const val STORAGE_TYPE_GOOGLE_PHOTOS = "google_photos"
    }

    private val sharedPreferences: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Migrate from old single-select to new multi-select format
        migrateFromSingleSelect()
    }

    /**
     * Migrate from old single-select storage to new multi-select format
     */
    private fun migrateFromSingleSelect() {
        val legacyStorageType = sharedPreferences.getString(KEY_PHOTO_STORAGE_TYPE, null)
        if (legacyStorageType != null) {
            // Migrate to new format
            val editor = sharedPreferences.edit()
            when (legacyStorageType) {
                STORAGE_TYPE_GOOGLE_DRIVE -> {
                    editor.putBoolean(KEY_GOOGLE_DRIVE_ENABLED, true)
                    editor.putBoolean(KEY_GOOGLE_PHOTOS_ENABLED, false)
                }
                STORAGE_TYPE_GOOGLE_PHOTOS -> {
                    editor.putBoolean(KEY_GOOGLE_DRIVE_ENABLED, false)
                    editor.putBoolean(KEY_GOOGLE_PHOTOS_ENABLED, true)
                }
            }
            // Remove legacy key
            editor.remove(KEY_PHOTO_STORAGE_TYPE)
            editor.apply()
        }
    }

    /**
     * Enable or disable Google Drive storage
     */
    fun setGoogleDriveEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_GOOGLE_DRIVE_ENABLED, enabled)
            .apply()
    }

    /**
     * Enable or disable Google Photos storage
     */
    fun setGooglePhotosEnabled(enabled: Boolean) {
        sharedPreferences.edit()
            .putBoolean(KEY_GOOGLE_PHOTOS_ENABLED, enabled)
            .apply()
    }

    /**
     * Check if Google Drive is enabled for photo storage
     */
    fun isGoogleDriveEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_GOOGLE_DRIVE_ENABLED, false)
    }

    /**
     * Check if Google Photos is enabled for photo storage
     */
    fun isGooglePhotosEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_GOOGLE_PHOTOS_ENABLED, false)
    }

    /**
     * Check if any photo storage is enabled
     */
    fun isAnyStorageEnabled(): Boolean {
        return isGoogleDriveEnabled() || isGooglePhotosEnabled()
    }

    /**
     * Check if both storage options are enabled
     */
    fun isBothStorageEnabled(): Boolean {
        return isGoogleDriveEnabled() && isGooglePhotosEnabled()
    }

    /**
     * Clear all photo storage selections
     */
    fun clearAllStorageSelections() {
        sharedPreferences.edit()
            .putBoolean(KEY_GOOGLE_DRIVE_ENABLED, false)
            .putBoolean(KEY_GOOGLE_PHOTOS_ENABLED, false)
            .apply()
    }

    // Legacy methods for backward compatibility
    @Deprecated("Use isGoogleDriveEnabled() instead")
    fun isGoogleDriveSelected(): Boolean {
        return isGoogleDriveEnabled()
    }

    @Deprecated("Use isGooglePhotosEnabled() instead")
    fun isGooglePhotosSelected(): Boolean {
        return isGooglePhotosEnabled()
    }

    @Deprecated("Use isAnyStorageEnabled() instead")
    fun isPhotoStorageTypeSelected(): Boolean {
        return isAnyStorageEnabled()
    }
}