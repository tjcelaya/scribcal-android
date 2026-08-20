package com.tjcelaya.scribcal.data

import android.content.Context
import android.content.SharedPreferences

enum class InstantEventIcon(val displayName: String, val iconResName: String) {
    ADD("Plus (Default)", "ic_add"),
    EDIT("Pencil", "ic_edit"),
    CHECK("Checkmark", "ic_check"),
    EVENT_AVAILABLE("Calendar with Check", "ic_event_available"),
    BOOKMARK("Bookmark", "ic_bookmark"),
    CIRCLE("Circle", "ic_circle"),
    NOTE_ADD("Note", "ic_note_add")
}

enum class BubbleMode(val displayName: String) {
    NEVER("Never"),
    SELECTED("Selected event types only"),
    ALWAYS("Always")
}

/**
 * How event types are displayed on the main tracking screen.
 */
enum class EventViewMode {
    LIST,
    CARD;

    companion object {
        fun fromName(value: String?): EventViewMode =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: LIST
    }
}

/**
 * How a card shows its event type's calendar color.
 */
enum class CardColorStyle(val displayName: String) {
    LINE("Line across top"),
    BACKGROUND("Full background");

    companion object {
        fun fromName(value: String?): CardColorStyle =
            value?.let { runCatching { valueOf(it) }.getOrNull() } ?: LINE
    }
}

class StoragePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "scribcal_storage_prefs"
        private const val KEY_GOOGLE_DRIVE_ENABLED = "google_drive_enabled"
        private const val KEY_GOOGLE_PHOTOS_ENABLED = "google_photos_enabled"
        private const val KEY_INSTANT_EVENT_ICON = "instant_event_icon"
        private const val KEY_BUBBLE_MODE = "bubble_mode"
        private const val KEY_EVENT_VIEW_MODE = "event_view_mode"
        private const val KEY_CARD_SIZE_DP = "event_card_size_dp"
        private const val KEY_CARD_COLOR_STYLE = "event_card_color_style"

        // Card size bounds (in dp) for the main-screen card grid.
        const val CARD_SIZE_MIN_DP = 110
        const val CARD_SIZE_MAX_DP = 260
        const val CARD_SIZE_DEFAULT_DP = 160

        // Service state persistence keys
        private const val KEY_DRIVE_INITIALIZED = "drive_initialized"
        private const val KEY_DRIVE_FOLDER_ID = "drive_folder_id"
        private const val KEY_DRIVE_LAST_TEST_TIME = "drive_last_test_time"
        private const val KEY_DRIVE_LAST_TEST_SUCCESS = "drive_last_test_success"
        private const val KEY_DRIVE_ACCOUNT_NAME = "drive_account_name"
        
        private const val KEY_PHOTOS_INITIALIZED = "photos_initialized"
        private const val KEY_PHOTOS_LAST_TEST_TIME = "photos_last_test_time"
        private const val KEY_PHOTOS_LAST_TEST_SUCCESS = "photos_last_test_success"
        private const val KEY_PHOTOS_ACCOUNT_NAME = "photos_account_name"

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

    // === Drive Service State Persistence ===
    
    /**
     * Save Drive service initialization state
     */
    fun saveDriveState(initialized: Boolean, folderId: String?, accountName: String?) {
        sharedPreferences.edit()
            .putBoolean(KEY_DRIVE_INITIALIZED, initialized)
            .putString(KEY_DRIVE_FOLDER_ID, folderId)
            .putString(KEY_DRIVE_ACCOUNT_NAME, accountName)
            .apply()
    }
    
    /**
     * Save Drive connection test results
     */
    fun saveDriveTestResult(testTime: Long, success: Boolean) {
        sharedPreferences.edit()
            .putLong(KEY_DRIVE_LAST_TEST_TIME, testTime)
            .putBoolean(KEY_DRIVE_LAST_TEST_SUCCESS, success)
            .apply()
    }
    
    /**
     * Get Drive initialization status
     */
    fun isDriveInitialized(): Boolean {
        return sharedPreferences.getBoolean(KEY_DRIVE_INITIALIZED, false)
    }
    
    /**
     * Get saved Drive folder ID
     */
    fun getDriveFolderId(): String? {
        return sharedPreferences.getString(KEY_DRIVE_FOLDER_ID, null)
    }
    
    /**
     * Get saved Drive account name
     */
    fun getDriveAccountName(): String? {
        return sharedPreferences.getString(KEY_DRIVE_ACCOUNT_NAME, null)
    }
    
    /**
     * Get Drive last test time
     */
    fun getDriveLastTestTime(): Long? {
        val time = sharedPreferences.getLong(KEY_DRIVE_LAST_TEST_TIME, -1L)
        return if (time == -1L) null else time
    }
    
    /**
     * Get Drive last test success status
     */
    fun wasDriveLastTestSuccessful(): Boolean {
        return sharedPreferences.getBoolean(KEY_DRIVE_LAST_TEST_SUCCESS, false)
    }
    
    /**
     * Clear Drive service state
     */
    fun clearDriveState() {
        sharedPreferences.edit()
            .remove(KEY_DRIVE_INITIALIZED)
            .remove(KEY_DRIVE_FOLDER_ID)
            .remove(KEY_DRIVE_ACCOUNT_NAME)
            .remove(KEY_DRIVE_LAST_TEST_TIME)
            .remove(KEY_DRIVE_LAST_TEST_SUCCESS)
            .apply()
    }
    
    // === Photos Service State Persistence ===
    
    /**
     * Save Photos service initialization state
     */
    fun savePhotosState(initialized: Boolean, accountName: String?) {
        sharedPreferences.edit()
            .putBoolean(KEY_PHOTOS_INITIALIZED, initialized)
            .putString(KEY_PHOTOS_ACCOUNT_NAME, accountName)
            .apply()
    }
    
    /**
     * Save Photos connection test results
     */
    fun savePhotosTestResult(testTime: Long, success: Boolean) {
        sharedPreferences.edit()
            .putLong(KEY_PHOTOS_LAST_TEST_TIME, testTime)
            .putBoolean(KEY_PHOTOS_LAST_TEST_SUCCESS, success)
            .apply()
    }
    
    /**
     * Get Photos initialization status
     */
    fun isPhotosInitialized(): Boolean {
        return sharedPreferences.getBoolean(KEY_PHOTOS_INITIALIZED, false)
    }
    
    /**
     * Get saved Photos account name
     */
    fun getPhotosAccountName(): String? {
        return sharedPreferences.getString(KEY_PHOTOS_ACCOUNT_NAME, null)
    }
    
    /**
     * Get Photos last test time
     */
    fun getPhotosLastTestTime(): Long? {
        val time = sharedPreferences.getLong(KEY_PHOTOS_LAST_TEST_TIME, -1L)
        return if (time == -1L) null else time
    }
    
    /**
     * Get Photos last test success status
     */
    fun wasPhotosLastTestSuccessful(): Boolean {
        return sharedPreferences.getBoolean(KEY_PHOTOS_LAST_TEST_SUCCESS, false)
    }
    
    /**
     * Clear Photos service state
     */
    fun clearPhotosState() {
        sharedPreferences.edit()
            .remove(KEY_PHOTOS_INITIALIZED)
            .remove(KEY_PHOTOS_ACCOUNT_NAME)
            .remove(KEY_PHOTOS_LAST_TEST_TIME)
            .remove(KEY_PHOTOS_LAST_TEST_SUCCESS)
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

    // === Instant Event Icon Preference ===

    /**
     * Get the selected instant event icon
     */
    fun getInstantEventIcon(): InstantEventIcon {
        val iconName = sharedPreferences.getString(KEY_INSTANT_EVENT_ICON, InstantEventIcon.ADD.name)
        return try {
            InstantEventIcon.valueOf(iconName ?: InstantEventIcon.ADD.name)
        } catch (e: IllegalArgumentException) {
            InstantEventIcon.ADD
        }
    }

    /**
     * Set the instant event icon preference
     */
    fun setInstantEventIcon(icon: InstantEventIcon) {
        sharedPreferences.edit()
            .putString(KEY_INSTANT_EVENT_ICON, icon.name)
            .apply()
    }

    /**
     * Get the drawable resource ID for the selected instant event icon
     */
    fun getInstantEventIconResourceId(context: Context): Int {
        val icon = getInstantEventIcon()
        val resourceId = context.resources.getIdentifier(
            icon.iconResName,
            "drawable",
            context.packageName
        )
        // Fallback to ic_edit if resource not found
        return if (resourceId != 0) resourceId else context.resources.getIdentifier(
            "ic_edit",
            "drawable",
            context.packageName
        )
    }

    // === Bubble Notification Preference ===

    /**
     * Get the bubble mode setting
     * Default is NEVER (silent notifications)
     */
    fun getBubbleMode(): BubbleMode {
        val modeName = sharedPreferences.getString(KEY_BUBBLE_MODE, BubbleMode.NEVER.name)
        return try {
            BubbleMode.valueOf(modeName ?: BubbleMode.NEVER.name)
        } catch (e: IllegalArgumentException) {
            BubbleMode.NEVER
        }
    }

    /**
     * Set the bubble mode
     */
    fun setBubbleMode(mode: BubbleMode) {
        sharedPreferences.edit()
            .putString(KEY_BUBBLE_MODE, mode.name)
            .apply()
    }

    /**
     * Check if bubbles are enabled in any mode
     * Used for backward compatibility and quick checks
     */
    fun areBubblesEnabled(): Boolean {
        return getBubbleMode() != BubbleMode.NEVER
    }

    // === Event View Mode (list vs card grid) ===

    fun getEventViewMode(): EventViewMode {
        return EventViewMode.fromName(sharedPreferences.getString(KEY_EVENT_VIEW_MODE, EventViewMode.LIST.name))
    }

    fun setEventViewMode(mode: EventViewMode) {
        sharedPreferences.edit()
            .putString(KEY_EVENT_VIEW_MODE, mode.name)
            .apply()
    }

    /**
     * Target card width/height in dp for the card grid, clamped to [CARD_SIZE_MIN_DP, CARD_SIZE_MAX_DP].
     */
    fun getCardSizeDp(): Int {
        val stored = sharedPreferences.getInt(KEY_CARD_SIZE_DP, CARD_SIZE_DEFAULT_DP)
        return stored.coerceIn(CARD_SIZE_MIN_DP, CARD_SIZE_MAX_DP)
    }

    fun setCardSizeDp(sizeDp: Int) {
        sharedPreferences.edit()
            .putInt(KEY_CARD_SIZE_DP, sizeDp.coerceIn(CARD_SIZE_MIN_DP, CARD_SIZE_MAX_DP))
            .apply()
    }

    // === Card color style (line across top vs full background) ===

    fun getCardColorStyle(): CardColorStyle {
        return CardColorStyle.fromName(sharedPreferences.getString(KEY_CARD_COLOR_STYLE, CardColorStyle.LINE.name))
    }

    fun setCardColorStyle(style: CardColorStyle) {
        sharedPreferences.edit()
            .putString(KEY_CARD_COLOR_STYLE, style.name)
            .apply()
    }
}
