package com.tjcelaya.scribcal.data

import android.content.Context
import android.content.SharedPreferences

class StoragePreferences(context: Context) {
    
    companion object {
        private const val PREFS_NAME = "scribcal_storage_prefs"
        private const val KEY_PHOTO_STORAGE_TYPE = "photo_storage_type"
        
        const val STORAGE_TYPE_GOOGLE_DRIVE = "google_drive"
        const val STORAGE_TYPE_GOOGLE_PHOTOS = "google_photos"
    }
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * Get the currently selected photo storage type
     */
    fun getPhotoStorageType(): String? {
        return sharedPreferences.getString(KEY_PHOTO_STORAGE_TYPE, null)
    }
    
    /**
     * Set the photo storage type
     */
    fun setPhotoStorageType(storageType: String) {
        sharedPreferences.edit()
            .putString(KEY_PHOTO_STORAGE_TYPE, storageType)
            .apply()
    }
    
    /**
     * Check if a photo storage type is selected
     */
    fun isPhotoStorageTypeSelected(): Boolean {
        return getPhotoStorageType() != null
    }
    
    /**
     * Clear the photo storage type selection
     */
    fun clearPhotoStorageType() {
        sharedPreferences.edit()
            .remove(KEY_PHOTO_STORAGE_TYPE)
            .apply()
    }
    
    /**
     * Check if Google Drive is selected as photo storage
     */
    fun isGoogleDriveSelected(): Boolean {
        return getPhotoStorageType() == STORAGE_TYPE_GOOGLE_DRIVE
    }
    
    /**
     * Check if Google Photos is selected as photo storage
     */
    fun isGooglePhotosSelected(): Boolean {
        return getPhotoStorageType() == STORAGE_TYPE_GOOGLE_PHOTOS
    }
}