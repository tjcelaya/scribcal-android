package com.tjcelaya.scribcal.data

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PhotosRepository(private val context: Context) {

    companion object {
        private const val TAG = "PhotosRepository"
        private const val SCRIBCAL_ALBUM_NAME = "ScribCal Events"
        private const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary"
    }

    private var isInitialized = false
    private var currentAccount: Account? = null
    private var lastConnectionTest: Long? = null

    /**
     * Initialize Google Photos service with the given account
     * This is a simplified version that just stores the account for now
     */
    suspend fun initializePhotos(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Photos service with account: ${account.name}")
            
            // Store the account for future use
            currentAccount = account
            isInitialized = true
            
            Log.d(TAG, "Photos service initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Photos service", e)
            false
        }
    }

    /**
     * Check if Photos service is initialized
     */
    fun isPhotosInitialized(): Boolean = isInitialized && currentAccount != null

    /**
     * Upload a photo to the ScribCal album and return a shareable link
     * This is a placeholder implementation for now
     */
    suspend fun uploadPhotoAndGetLink(localFilePath: String, fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            if (!isPhotosInitialized()) {
                Log.e(TAG, "Photos service not initialized")
                return@withContext null
            }
            
            Log.d(TAG, "Uploading photo: $fileName (placeholder implementation)")
            
            val localFile = java.io.File(localFilePath)
            if (!localFile.exists()) {
                Log.e(TAG, "Local photo file does not exist: $localFilePath")
                return@withContext null
            }
            
            // TODO: Implement actual Google Photos upload
            // For now, return a placeholder URL
            Log.d(TAG, "Photo upload placeholder - would upload: $fileName")
            return@withContext "https://photos.google.com/placeholder/$fileName"
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload photo and get link", e)
            null
        }
    }

    /**
     * Test Photos connection by checking permissions
     */
    suspend fun testPhotosConnection(): PhotosConnectionResult = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!isPhotosInitialized()) {
                return@withContext PhotosConnectionResult(
                    isConnected = false,
                    status = "Photos service not initialized",
                    lastTestTime = null
                )
            }

            Log.d(TAG, "Testing Photos connection...")

            val account = currentAccount!!
            val hasPermission = checkPhotosPermission(account)
            
            val timestamp = System.currentTimeMillis()
            lastConnectionTest = timestamp

            if (hasPermission) {
                Log.d(TAG, "Photos connection test successful")
                PhotosConnectionResult(
                    isConnected = true,
                    status = "OK",
                    lastTestTime = timestamp
                )
            } else {
                Log.d(TAG, "Photos permission not available")
                PhotosConnectionResult(
                    isConnected = false,
                    status = "No permission",
                    lastTestTime = timestamp
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Photos connection test failed", e)
            val timestamp = System.currentTimeMillis()
            lastConnectionTest = timestamp

            PhotosConnectionResult(
                isConnected = false,
                status = "Failed",
                lastTestTime = timestamp
            )
        }
    }

    /**
     * Get the current Photos album name
     */
    fun getCurrentAlbumName(): String = SCRIBCAL_ALBUM_NAME

    /**
     * Get Photos connection status without testing
     */
    fun getPhotosStatus(): String {
        return when {
            !isInitialized -> "Not initialized"
            currentAccount == null -> "No account"
            lastConnectionTest != null -> {
                val timeAgo = getTimeAgo(lastConnectionTest!!)
                "OK ($timeAgo ago)"
            }
            else -> "Initialized"
        }
    }

    /**
     * Get time since last connection test
     */
    fun getLastTestTime(): Long? = lastConnectionTest

    /**
     * Format time difference as a short string
     */
    private fun getTimeAgo(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "${diff / 1000}s" // seconds
            diff < 3600_000 -> "${diff / 60_000}m" // minutes
            diff < 86400_000 -> "${diff / 3600_000}h" // hours
            else -> "${diff / 86400_000}d" // days
        }
    }

    /**
     * Check if we have permission to access Photos
     */
    suspend fun checkPhotosPermission(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            val token = GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:$PHOTOS_SCOPE"
            )

            // If we can get a token, we have permission
            token != null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Photos permission", e)
            false
        }
    }
}

/**
 * Data class to hold Photos connection test results
 */
data class PhotosConnectionResult(
    val isConnected: Boolean,
    val status: String,
    val lastTestTime: Long?
)