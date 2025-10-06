package com.tjcelaya.scribcal.data

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import java.io.RandomAccessFile
import com.tjcelaya.scribcal.data.database.ScribCalDatabase
import com.tjcelaya.scribcal.data.database.AlbumConfig

class PhotosRepository(
    private val context: Context,
    private val database: ScribCalDatabase
) {

    companion object {
        private const val TAG = "PhotosRepository"
        private const val SCRIBCAL_ALBUM_NAME = "ScribCal Events"
        // Request full scope for both read and write permissions
        private const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary"
        private const val PHOTOS_READONLY_SCOPE = "https://www.googleapis.com/auth/photoslibrary.readonly"

        // Album verification constants
        private const val ALBUM_VERIFICATION_INTERVAL_MS = 30 * 60 * 1000L // 30 minutes
        private const val ALBUM_VERIFICATION_GRACE_PERIOD_MS = 5 * 60 * 1000L // 5 minutes grace period

        // SharedPreferences keys
        private const val PREFS_NAME = "photos_repository_prefs"
        private const val KEY_SCRIBCAL_ALBUM_ID = "scribcal_album_id"
        private const val KEY_ALBUM_LAST_VERIFIED = "album_last_verified"
        private const val KEY_LAST_CONNECTION_TEST = "last_connection_test"
        private const val KEY_LAST_CONNECTION_SUCCESSFUL = "last_connection_successful"
        private const val KEY_CURRENT_ACCOUNT_NAME = "current_account_name"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var currentAccount: Account? = null
    private var isInitialized = false
    private var scribcalAlbumReady = false
    private var scribcalAlbumId: String? = null
    private var lastConnectionTest: Long? = null
    private var lastConnectionSuccessful = false

    init {
        // Load persisted connection state
        loadPersistedState()
    }

    /**
     * Initialize async data loading (call after construction)
     */
    suspend fun initializeAsyncData() {
        loadAlbumConfiguration()
    }

    /**
     * Load persisted connection state from SharedPreferences
     */
    private fun loadPersistedState() {
        try {
            // Load connection test state
            val lastTestTime = prefs.getLong(KEY_LAST_CONNECTION_TEST, 0L)
            lastConnectionTest = if (lastTestTime > 0) lastTestTime else null

            lastConnectionSuccessful = prefs.getBoolean(KEY_LAST_CONNECTION_SUCCESSFUL, false)

            // Load account info
            val accountName = prefs.getString(KEY_CURRENT_ACCOUNT_NAME, null)
            if (accountName != null) {
                currentAccount = Account(accountName, "com.google")
                isInitialized = true
            }

            Log.d(TAG, "Loaded persisted state - lastTest: $lastConnectionTest, successful: $lastConnectionSuccessful, account: $accountName")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to load persisted state: ${e.message}")
        }
    }

    /**
     * Load album configuration from database
     */
    private suspend fun loadAlbumConfiguration() {
        try {
            val albumConfig = database.albumConfigDao().getAlbumConfig()
            if (albumConfig?.googlePhotosAlbumId != null) {
                scribcalAlbumId = albumConfig.googlePhotosAlbumId
                scribcalAlbumReady = true
                Log.d(TAG, "Loaded album configuration from database: ${albumConfig.googlePhotosAlbumId}")
            } else {
                Log.d(TAG, "No album configuration found in database")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load album configuration: ${e.message}")
        }
    }

    /**
     * Save connection state to SharedPreferences
     */
    private fun saveConnectionState() {
        try {
            val editor = prefs.edit()

            // Save connection test state
            if (lastConnectionTest != null) {
                editor.putLong(KEY_LAST_CONNECTION_TEST, lastConnectionTest!!)
            } else {
                editor.remove(KEY_LAST_CONNECTION_TEST)
            }

            editor.putBoolean(KEY_LAST_CONNECTION_SUCCESSFUL, lastConnectionSuccessful)

            // Save current account
            if (currentAccount != null) {
                editor.putString(KEY_CURRENT_ACCOUNT_NAME, currentAccount!!.name)
            } else {
                editor.remove(KEY_CURRENT_ACCOUNT_NAME)
            }

            editor.apply()

            Log.d(TAG, "Saved connection state - lastTest: $lastConnectionTest, successful: $lastConnectionSuccessful")

        } catch (e: Exception) {
            Log.w(TAG, "Failed to save connection state: ${e.message}")
        }
    }

    /**
     * Initialize Google Photos service with the given account
     * For now, this validates the account and prepares for future API implementation
     */
    suspend fun initializePhotos(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Photos service with account: ${account.name}")

            // Test if we can get OAuth permissions for Photos
            val (hasPermission, needsUserConsent) = checkPhotosPermission(account)
            if (hasPermission) {
                currentAccount = account
                isInitialized = true
                scribcalAlbumReady = true // Assume album will be created when needed

                Log.d(TAG, "Photos service initialized successfully with permission validation")
                true
            } else if (needsUserConsent) {
                // Still set up the account but mark as needing consent
                currentAccount = account
                isInitialized = true
                scribcalAlbumReady = false // Not ready until consent is granted

                Log.d(TAG, "Photos service initialized but user consent required")
                true // Return true so UI can show consent screen
            } else {
                Log.w(TAG, "Photos permission not available for account: ${account.name}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Photos service", e)
            currentAccount = null
            isInitialized = false
            scribcalAlbumReady = false
            false
        }
    }

    /**
     * Check if Photos service is initialized
     */
    fun isPhotosInitialized(): Boolean = isInitialized && currentAccount != null

    /**
     * Check if Photos service is ready for photo operations (initialized and tested successfully)
     * This method will trigger background album verification if needed
     */
    fun isPhotosReady(): Boolean {
        val basicReadiness = isPhotosInitialized() && scribcalAlbumReady && lastConnectionSuccessful

        if (!basicReadiness) {
            return false
        }

        // Check if we need to verify the album still exists remotely
        if (shouldVerifyAlbumRemotely()) {
            Log.d(TAG, "Album verification needed, triggering background check")
            // Trigger async verification without blocking
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    verifyAlbumStillExists()
                } catch (e: Exception) {
                    Log.w(TAG, "Background album verification failed", e)
                }
            }

            // For now, return true if we're within the grace period
            return isWithinGracePeriod()
        }

        return true
    }

    /**
     * Unified health check for Google Photos integration
     * Returns true if Photos is currently healthy and can be used for photo storage
     * This is the single source of truth for Photos health status
     */
    fun isHealthy(): Boolean {
        // Basic requirements: service must be initialized
        if (!isPhotosInitialized()) {
            Log.d(TAG, "Photos not healthy: not initialized")
            return false
        }

        // Must have a selected album configured
        if (!scribcalAlbumReady || scribcalAlbumId == null) {
            Log.d(TAG, "Photos not healthy: no album configured (albumReady=$scribcalAlbumReady, albumId=$scribcalAlbumId)")
            return false
        }

        // Must have had at least one successful connection test
        if (!lastConnectionSuccessful) {
            Log.d(TAG, "Photos not healthy: no successful connection test (lastConnectionSuccessful=$lastConnectionSuccessful)")
            return false
        }

        // If we haven't tested at all, not healthy
        if (lastConnectionTest == null) {
            Log.d(TAG, "Photos not healthy: never tested (lastConnectionTest=null)")
            return false
        }

        // Check if album verification is overdue and we're past grace period
        if (shouldVerifyAlbumRemotely() && !isWithinGracePeriod()) {
            Log.d(TAG, "Photos not healthy: album verification overdue and past grace period")
            // Trigger background verification
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    verifyAlbumStillExists()
                } catch (e: Exception) {
                    Log.w(TAG, "Background album verification failed", e)
                }
            }
            return false
        }

        Log.d(TAG, "Photos healthy: all checks passed")
        return true
    }

    /**
     * Check if we should verify the album exists remotely based on time elapsed
     */
    private fun shouldVerifyAlbumRemotely(): Boolean {
        val lastVerified = getLastAlbumVerificationTime()
        if (lastVerified == 0L) {
            Log.d(TAG, "No previous album verification recorded")
            return true
        }

        val timeSinceVerification = System.currentTimeMillis() - lastVerified
        val needsVerification = timeSinceVerification > ALBUM_VERIFICATION_INTERVAL_MS

        Log.d(TAG, "Album last verified ${timeSinceVerification / 1000}s ago, needs verification: $needsVerification")
        return needsVerification
    }

    /**
     * Check if we're still within the grace period for album verification
     */
    private fun isWithinGracePeriod(): Boolean {
        val lastVerified = getLastAlbumVerificationTime()
        if (lastVerified == 0L) {
            return false
        }

        val timeSinceVerification = System.currentTimeMillis() - lastVerified
        val withinGracePeriod = timeSinceVerification <= (ALBUM_VERIFICATION_INTERVAL_MS + ALBUM_VERIFICATION_GRACE_PERIOD_MS)

        Log.d(TAG, "Grace period check: ${timeSinceVerification / 1000}s since verification, within grace period: $withinGracePeriod")
        return withinGracePeriod
    }

    /**
     * Get the last album verification time from database (synchronous)
     */
    private fun getLastAlbumVerificationTime(): Long {
        return try {
            // Use runBlocking for synchronous access - this is acceptable for cached/fast database reads
            kotlinx.coroutines.runBlocking {
                database.albumConfigDao().getAlbumConfig()?.lastVerified ?: 0L
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last album verification time", e)
            0L
        }
    }

    /**
     * Verify that the currently selected album still exists remotely
     * This is called asynchronously and updates the album state
     */
    private suspend fun verifyAlbumStillExists() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting background album verification")

            val account = currentAccount ?: run {
                Log.w(TAG, "No current account for album verification")
                return@withContext
            }

            val albumConfig = database.albumConfigDao().getAlbumConfig()
            val albumId = albumConfig?.googlePhotosAlbumId

            if (albumId == null) {
                Log.w(TAG, "No album ID to verify")
                scribcalAlbumReady = false
                return@withContext
            }

            // Get OAuth token
            val token = try {
                GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get token for album verification", e)
                // Don't mark album as not ready due to token issues - might be temporary
                return@withContext
            }

            if (token.isEmpty()) {
                Log.w(TAG, "Empty token for album verification")
                return@withContext
            }

            // Verify album exists
            val albumExists = verifyAlbumExists(token, albumId)

            if (albumExists) {
                Log.d(TAG, "Background verification: album still exists")
                // Update last verified time
                database.albumConfigDao().updateLastVerified(System.currentTimeMillis())
                scribcalAlbumReady = true
            } else {
                Log.w(TAG, "Background verification: album no longer exists, marking as not ready")
                scribcalAlbumReady = false
                lastConnectionSuccessful = false

                // Clear the invalid album configuration
                clearAlbumConfiguration()
            }

        } catch (e: Exception) {
            Log.w(TAG, "Background album verification failed with exception", e)
            // Don't change album state on exception - might be network issue
        }
    }

    /**
     * Upload a photo to Google Photos and return a shareable link
     */
    suspend fun uploadPhotoAndGetLink(
        localFilePath: String,
        fileName: String,
        progressCallback: ((Int) -> Unit)? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!isPhotosReady()) {
                Log.e(TAG, "Photos service not ready for uploads")
                return@withContext null
            }

            // Note: We skip pre-upload verification here because it can cause unnecessary failures
            // due to transient token issues. If there are real access problems, they'll be caught
            // during the actual upload process and can be handled with retry logic.

            Log.d(TAG, "Uploading photo to Google Photos: $fileName")
            progressCallback?.invoke(5)

            val localFile = java.io.File(localFilePath)
            if (!localFile.exists()) {
                Log.e(TAG, "Local photo file does not exist: $localFilePath")
                return@withContext null
            }

            progressCallback?.invoke(15)

            // Since the Google Photos Library API has compilation issues with protobuf classes,
            // we'll use the REST API approach for now
            val result = uploadPhotoViaRestApi(localFile, fileName, progressCallback)

            if (result != null) {
                Log.d(TAG, "Photo uploaded successfully to Google Photos: $result")
                progressCallback?.invoke(100)
            } else {
                Log.e(TAG, "Failed to upload photo to Google Photos")
            }

            result

        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload photo to Google Photos", e)
            null
        }
    }

    /**
     * Upload photo using Google Photos REST API with token refresh retry
     */
    private suspend fun uploadPhotoViaRestApi(
        file: java.io.File,
        fileName: String,
        progressCallback: ((Int) -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        var result = attemptUpload(file, fileName, progressCallback)

        // If first attempt failed, try token refresh and retry once
        if (result == null) {
            Log.i(TAG, "First upload attempt failed, trying token refresh...")
            val refreshed = refreshOAuthToken()
            if (refreshed) {
                Log.d(TAG, "Token refreshed, retrying upload...")
                result = attemptUpload(file, fileName, progressCallback)
            }
        }

        result
    }

    /**
     * Attempt to upload photo once without retry logic
     */
    private suspend fun attemptUpload(
        file: java.io.File,
        fileName: String,
        progressCallback: ((Int) -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val account = currentAccount ?: return@withContext null

            progressCallback?.invoke(25)

            // Get OAuth token for Google Photos API
            val token = GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:$PHOTOS_SCOPE"
            )

            if (token == "") {
                Log.e(TAG, "Failed to get OAuth token for Google Photos")
                return@withContext null
            }

            progressCallback?.invoke(40)

            // Step 1: Upload photo bytes to get upload token
            val uploadToken = uploadPhotoBytes(file, token, progressCallback)
            if (uploadToken == null) {
                Log.e(TAG, "Failed to get upload token")
                return@withContext null
            }

            progressCallback?.invoke(70)

            // Step 2: Create media item in Google Photos and get response with URLs
            val createResult = createMediaItemWithResponse(uploadToken, fileName, token)
            if (createResult == null) {
                Log.e(TAG, "Failed to create media item")
                return@withContext null
            }

            val (mediaItemId, responseJson) = createResult
            progressCallback?.invoke(85)

            // Step 3: Add to ScribCal album (required for ScribCal photos)
            val albumId = scribcalAlbumId
            if (albumId == null) {
                Log.e(TAG, "No ScribCal album configured - this is required for photo uploads. User should configure an album in Settings first.")
                return@withContext null
            }

            val success = addToScribCalAlbum(mediaItemId, token)
            if (!success) {
                Log.e(TAG, "Failed to add photo to ScribCal album - upload failed")
                return@withContext null
            }

            progressCallback?.invoke(95)

            // Try to get the shareable URL directly from the create response to avoid additional API calls
            val photoUrl = extractPhotoUrlFromCreateResponse(responseJson) ?: getMediaItemShareableUrl(mediaItemId, token)
            Log.d(TAG, "Successfully uploaded photo to Google Photos: $photoUrl")

            photoUrl

        } catch (e: Exception) {
            Log.e(TAG, "Upload attempt failed", e)
            null
        }
    }

    /**
     * Upload photo bytes to Google Photos and get upload token
     */
    private suspend fun uploadPhotoBytes(
        file: java.io.File,
        accessToken: String,
        progressCallback: ((Int) -> Unit)?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://photoslibrary.googleapis.com/v1/uploads")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("X-Goog-Upload-File-Name", file.name)
            connection.setRequestProperty("X-Goog-Upload-Protocol", "raw")
            connection.doOutput = true

            // Upload file bytes
            connection.outputStream.use { outputStream ->
                file.inputStream().use { inputStream ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    val fileSize = file.length()

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead

                        // Update progress during upload
                        if (fileSize > 0) {
                            val progress = 40 + ((totalBytes.toFloat() / fileSize) * 25).toInt()
                            progressCallback?.invoke(progress)
                        }
                    }
                }
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val uploadToken = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Photo bytes uploaded, got upload token")
                uploadToken
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Upload failed with code $responseCode: $errorStream")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload photo bytes", e)
            null
        }
    }

    /**
     * Create media item from upload token and return both the ID and response JSON
     */
    private suspend fun createMediaItemWithResponse(
        uploadToken: String,
        fileName: String,
        accessToken: String
    ): Pair<String, JSONObject>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Create request JSON
            val requestJson = JSONObject().apply {
                put("newMediaItems", JSONArray().apply {
                    put(JSONObject().apply {
                        put("description", "ScribCal event photo")
                        put("simpleMediaItem", JSONObject().apply {
                            put("uploadToken", uploadToken)
                            put("fileName", fileName)
                        })
                    })
                })
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(requestJson.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Create media item response code: $responseCode")

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Create media item response: $response")

                val responseJson = JSONObject(response)
                val results = responseJson.getJSONArray("newMediaItemResults")

                if (results.length() > 0) {
                    val result = results.getJSONObject(0)
                    Log.d(TAG, "First result: $result")

                    // Check if this result has a mediaItem (success case)
                    if (result.has("mediaItem")) {
                        val mediaItem = result.getJSONObject("mediaItem")
                        val mediaItemId = mediaItem.getString("id")
                        Log.d(TAG, "Media item created successfully: $mediaItemId")
                        return@withContext Pair(mediaItemId, result)
                    }

                    // Check if this result has a status (error case)
                    if (result.has("status")) {
                        val status = result.getJSONObject("status")
                        Log.d(TAG, "Status object: $status")

                        val code = status.optInt("code", -1)
                        val message = status.optString("message", "Unknown error")

                        if (code == 0) {
                            // Success but no mediaItem? This shouldn't happen
                            Log.e(TAG, "Success status but no mediaItem in result")
                        } else {
                            Log.e(TAG, "Media item creation failed with code $code: $message")
                        }
                    } else {
                        Log.e(TAG, "Result has neither mediaItem nor status: $result")
                    }
                } else {
                    Log.e(TAG, "No results in response")
                }
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Create media item failed with code $responseCode: $errorStream")
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create media item", e)
            null
        }
    }

    /**
     * Create media item from upload token (legacy method - returns only ID)
     */
    private suspend fun createMediaItem(
        uploadToken: String,
        fileName: String,
        accessToken: String
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://photoslibrary.googleapis.com/v1/mediaItems:batchCreate")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            // Create request JSON
            val requestJson = JSONObject().apply {
                put("newMediaItems", JSONArray().apply {
                    put(JSONObject().apply {
                        put("description", "ScribCal event photo")
                        put("simpleMediaItem", JSONObject().apply {
                            put("uploadToken", uploadToken)
                            put("fileName", fileName)
                        })
                    })
                })
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(requestJson.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Create media item response code: $responseCode")

            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Create media item response: $response")

                val responseJson = JSONObject(response)
                val results = responseJson.getJSONArray("newMediaItemResults")

                if (results.length() > 0) {
                    val result = results.getJSONObject(0)
                    Log.d(TAG, "First result: $result")

                    // Check if this result has a mediaItem (success case)
                    if (result.has("mediaItem")) {
                        val mediaItem = result.getJSONObject("mediaItem")
                        val mediaItemId = mediaItem.getString("id")
                        Log.d(TAG, "Media item created successfully: $mediaItemId")
                        return@withContext mediaItemId
                    }

                    // Check if this result has a status (error case)
                    if (result.has("status")) {
                        val status = result.getJSONObject("status")
                        Log.d(TAG, "Status object: $status")

                        val code = status.optInt("code", -1)
                        val message = status.optString("message", "Unknown error")

                        if (code == 0) {
                            // Success but no mediaItem? This shouldn't happen
                            Log.e(TAG, "Success status but no mediaItem in result")
                        } else {
                            Log.e(TAG, "Media item creation failed with code $code: $message")
                        }
                    } else {
                        Log.e(TAG, "Result has neither mediaItem nor status: $result")
                    }
                } else {
                    Log.e(TAG, "No results in response")
                }
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Create media item failed with code $responseCode: $errorStream")
            }

            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create media item", e)
            null
        }
    }

    /**
     * Extract photo URL directly from the create media item response to avoid additional API calls
     */
    private fun extractPhotoUrlFromCreateResponse(createResultJson: JSONObject): String? {
        return try {
            if (!createResultJson.has("mediaItem")) {
                Log.d(TAG, "No mediaItem in create result")
                return null
            }

            val mediaItem = createResultJson.getJSONObject("mediaItem")
            Log.d(TAG, "MediaItem from create response: $mediaItem")

            // Priority 1: baseUrl - provides direct access to the image file
            // Note: baseUrl expires after about 60 minutes but works immediately
            if (mediaItem.has("baseUrl")) {
                val baseUrl = mediaItem.getString("baseUrl")
                Log.d(TAG, "Using baseUrl from create response for direct image access: $baseUrl")
                // Add parameters for better web viewing
                return "$baseUrl=w2048-h2048"
            }

            // Priority 2: productUrl - links to Google Photos web interface
            if (mediaItem.has("productUrl")) {
                val productUrl = mediaItem.getString("productUrl")
                Log.d(TAG, "Using productUrl from create response for Google Photos link: $productUrl")
                return productUrl
            }

            Log.w(TAG, "No baseUrl or productUrl found in create response mediaItem")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract photo URL from create response", e)
            null
        }
    }

    /**
     * Get the shareable URL for a media item
     * This prioritizes baseUrl for direct access, falling back to productUrl for sharing
     */
    private suspend fun getMediaItemShareableUrl(mediaItemId: String, accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            // Get the media item details to access baseUrl and productUrl
            val url = URL("https://photoslibrary.googleapis.com/v1/mediaItems/$mediaItemId")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(response)

                Log.d(TAG, "Media item response: $response")

                // Priority 1: baseUrl - provides direct access to the image file
                // Note: baseUrl expires after about 60 minutes but works immediately
                if (responseJson.has("baseUrl")) {
                    val baseUrl = responseJson.getString("baseUrl")
                    Log.d(TAG, "Using baseUrl for direct image access: $baseUrl")
                    // Add parameters for better web viewing
                    return@withContext "$baseUrl=w2048-h2048"
                }

                // Priority 2: productUrl - links to Google Photos web interface
                if (responseJson.has("productUrl")) {
                    val productUrl = responseJson.getString("productUrl")
                    Log.d(TAG, "Using productUrl for Google Photos link: $productUrl")
                    return@withContext productUrl
                }

                Log.w(TAG, "No baseUrl or productUrl found in media item response")
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Failed to get media item details with code $responseCode: $errorStream")
            }

            // Fallback: Return a simple Google Photos link (may not work without authentication)
            val fallbackUrl = "https://photos.google.com/photo/$mediaItemId"
            Log.w(TAG, "Using fallback URL: $fallbackUrl")
            return@withContext fallbackUrl

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get shareable URL for media item: $mediaItemId", e)
            // Final fallback
            val fallbackUrl = "https://photos.google.com/photo/$mediaItemId"
            Log.w(TAG, "Exception occurred, using fallback URL: $fallbackUrl")
            return@withContext fallbackUrl
        }
    }

    /**
     * Add media item to ScribCal album
     */
    private suspend fun addToScribCalAlbum(mediaItemId: String, accessToken: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Use cached album ID (should be set during connection test)
            val albumId = scribcalAlbumId
            if (albumId == null) {
                Log.w(TAG, "ScribCal album ID not available - connection test may have failed")
                return@withContext false
            }

            // Add media item to album
            val url = URL("https://photoslibrary.googleapis.com/v1/albums/$albumId:batchAddMediaItems")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val requestJson = JSONObject().apply {
                put("mediaItemIds", JSONArray().apply {
                    put(mediaItemId)
                })
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(requestJson.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                Log.d(TAG, "Successfully added photo to ScribCal album")
                true
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.w(TAG, "Failed to add to album with code $responseCode: $errorStream")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to add to ScribCal album", e)
            false
        }
    }

    /**
     * Set the selected album for ScribCal photos (replaces auto-creation approach)
     */
    suspend fun setSelectedAlbum(albumId: String, albumTitle: String): Boolean {
        return try {
            val albumConfigDao = database.albumConfigDao()

            Log.d(TAG, "Setting selected album: $albumTitle ($albumId)")

            val config = AlbumConfig(
                id = 1, // Always use ID 1 since we only have one album config
                googlePhotosAlbumId = albumId,
                googlePhotosAlbumName = albumTitle,
                createdAt = System.currentTimeMillis(),
                lastVerified = System.currentTimeMillis()
            )

            // Save to database
            Log.d(TAG, "Inserting album config to database...")
            albumConfigDao.insertAlbumConfig(config)

            // Verify the save worked by reading it back
            val savedConfig = albumConfigDao.getAlbumConfig()
            if (savedConfig?.googlePhotosAlbumId == albumId) {
                Log.d(TAG, "Database save verified: ${savedConfig.googlePhotosAlbumName} (${savedConfig.googlePhotosAlbumId})")
            } else {
                Log.w(TAG, "Database save verification failed! Expected $albumId but got ${savedConfig?.googlePhotosAlbumId}")
            }

            // Update in-memory cache
            scribcalAlbumId = albumId
            scribcalAlbumReady = true

            Log.d(TAG, "Selected album set successfully: $albumTitle ($albumId)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set selected album", e)
            false
        }
    }

    /**
     * Get the currently selected album info
     */
    suspend fun getSelectedAlbum(): AlbumConfig? {
        return try {
            database.albumConfigDao().getAlbumConfig()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get selected album", e)
            null
        }
    }

    /**
     * Find or create the ScribCal album using database storage with remote verification
     * @deprecated Use setSelectedAlbum instead of auto-creating albums
     */
    @Deprecated("Use setSelectedAlbum to let users choose their album instead")
    private suspend fun findOrCreateScribCalAlbum(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val albumConfigDao = database.albumConfigDao()

            // Check if we have an album ID stored in database
            val albumConfig = albumConfigDao.getAlbumConfig()

            if (albumConfig?.googlePhotosAlbumId != null) {
                Log.d(TAG, "Found stored album ID: ${albumConfig.googlePhotosAlbumId}, verifying it still exists...")

                // Verify the album actually exists in Google Photos
                val albumExists = verifyAlbumExists(accessToken, albumConfig.googlePhotosAlbumId)

                if (albumExists) {
                    Log.d(TAG, "Album verified to exist remotely: ${albumConfig.googlePhotosAlbumId}")

                    // Update in-memory cache
                    scribcalAlbumId = albumConfig.googlePhotosAlbumId

                    // Update last verified time
                    albumConfigDao.updateLastVerified(System.currentTimeMillis())

                    return@withContext albumConfig.googlePhotosAlbumId
                } else {
                    Log.w(TAG, "Stored album no longer exists remotely, will create new one")

                    // Clear the invalid album config from database
                    albumConfigDao.clearAlbumConfig()

                    // Reset in-memory state
                    scribcalAlbumId = null
                    scribcalAlbumReady = false
                }
            }

            // No stored album OR stored album was deleted, create a new one
            Log.d(TAG, "Creating new ScribCal album...")
            val newAlbumId = createScribCalAlbum(accessToken)

            if (newAlbumId != null) {
                Log.d(TAG, "Created new album: $newAlbumId, saving to database")

                // Save to database - use upsert to handle both new creation and replacement
                val config = AlbumConfig(
                    id = 1, // Always use ID 1 since we only have one album config
                    googlePhotosAlbumId = newAlbumId,
                    googlePhotosAlbumName = SCRIBCAL_ALBUM_NAME,
                    createdAt = System.currentTimeMillis(),
                    lastVerified = System.currentTimeMillis()
                )

                // Use insertAlbumConfig which should handle replace/upsert
                albumConfigDao.insertAlbumConfig(config)

                // Update in-memory cache
                scribcalAlbumId = newAlbumId
                scribcalAlbumReady = true

                Log.d(TAG, "ScribCal album created/updated successfully with new ID: $newAlbumId")
            } else {
                Log.e(TAG, "Failed to create new ScribCal album")
            }

            return@withContext newAlbumId

        } catch (e: Exception) {
            Log.e(TAG, "Failed to find or create ScribCal album", e)
            null
        }
    }

    /**
     * Verify that an album still exists in Google Photos
     */
    private suspend fun verifyAlbumExists(accessToken: String, albumId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Verifying album exists: $albumId")
            val url = URL("https://photoslibrary.googleapis.com/v1/albums/$albumId")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")

            val responseCode = connection.responseCode
            when (responseCode) {
                200 -> {
                    Log.d(TAG, "Album verified to exist: $albumId")
                    true
                }
                404 -> {
                    Log.w(TAG, "Album not found (deleted): $albumId")
                    false
                }
                403 -> {
                    Log.w(TAG, "Album access forbidden (permissions issue): $albumId")
                    false
                }
                else -> {
                    val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.w(TAG, "Album verification failed with code $responseCode: $errorStream - Album: $albumId")
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to verify album exists: $albumId", e)
            false
        }
    }

    /**
     * Data class for album information
     */
    data class AlbumInfo(
        val id: String,
        val title: String,
        val totalMediaItems: Int = 0
    )


    /**
     * Create the ScribCal album
     */
    private suspend fun createScribCalAlbum(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://photoslibrary.googleapis.com/v1/albums")
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            val requestJson = JSONObject().apply {
                put("album", JSONObject().apply {
                    put("title", SCRIBCAL_ALBUM_NAME)
                })
            }

            connection.outputStream.use { outputStream ->
                outputStream.write(requestJson.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(response)
                val albumId = responseJson.getString("id")
                Log.d(TAG, "Created ScribCal album: $albumId")
                albumId
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Failed to create album with code $responseCode: $errorStream")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create ScribCal album", e)
            null
        }
    }

    /**
     * Test Photos connection by validating OAuth permissions and album availability
     *
     * IMPORTANT: This method ALWAYS performs full remote verification regardless of timing
     * when the user explicitly initiates a connection test. This ensures we detect
     * deleted albums, revoked permissions, or other issues immediately.
     */
    suspend fun testPhotosConnection(): PhotosConnectionResult = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!isPhotosInitialized()) {
                Log.w(TAG, "Photos not initialized - isInitialized: $isInitialized, account: ${currentAccount?.name}")
                return@withContext PhotosConnectionResult(
                    isConnected = false,
                    status = "Not initialized",
                    lastTestTime = null
                )
            }

            Log.d(TAG, "Testing Photos connection for account: ${currentAccount?.name}")

            val account = currentAccount!!
            val (hasPermission, needsUserConsent) = checkPhotosPermission(account)

            val timestamp = System.currentTimeMillis()
            lastConnectionTest = timestamp

            if (needsUserConsent) {
                lastConnectionSuccessful = false
                Log.w(TAG, "User consent required for Photos access")

                // Save connection state
                saveConnectionState()

                PhotosConnectionResult(
                    isConnected = false,
                    status = "Setup required",
                    lastTestTime = timestamp
                )
            } else if (hasPermission) {
                // Test by actually accessing the API and ensuring album exists
                Log.d(TAG, "OAuth permission valid, testing API access...")

                val token = GoogleAuthUtil.getToken(
                    context,
                    account,
                    "oauth2:$PHOTOS_SCOPE"
                )

                if (token != "") {
                    // Check if user has selected an album
                    val selectedAlbum = getSelectedAlbum()

                    if (selectedAlbum?.googlePhotosAlbumId != null) {
                        // Test API access by verifying the selected album still exists
                        val albumExists = verifyAlbumExists(token, selectedAlbum.googlePhotosAlbumId)

                        if (albumExists) {
                            lastConnectionSuccessful = true
                            scribcalAlbumReady = true
                            scribcalAlbumId = selectedAlbum.googlePhotosAlbumId
                            Log.d(TAG, "Photos connection test successful - selected album verified: ${selectedAlbum.googlePhotosAlbumName}")

                            // Update last verified time
                            database.albumConfigDao().updateLastVerified(System.currentTimeMillis())

                            // Save connection state
                            saveConnectionState()

                            PhotosConnectionResult(
                                isConnected = true,
                                status = "OK",
                                lastTestTime = timestamp
                            )
                        } else {
                            lastConnectionSuccessful = false
                            scribcalAlbumReady = false
                            scribcalAlbumId = null
                            Log.w(TAG, "Selected album no longer exists: ${selectedAlbum.googlePhotosAlbumName}")

                            // Clear the invalid album configuration from database
                            clearAlbumConfiguration()

                            // Save connection state
                            saveConnectionState()

                            PhotosConnectionResult(
                                isConnected = false,
                                status = "Selected album not found - please choose a different album",
                                lastTestTime = timestamp
                            )
                        }
                    } else {
                        lastConnectionSuccessful = false
                        scribcalAlbumReady = false
                        scribcalAlbumId = null
                        Log.i(TAG, "No album selected yet")

                        // Save connection state
                        saveConnectionState()

                        PhotosConnectionResult(
                            isConnected = false,
                            status = "No album selected - please choose an album",
                            lastTestTime = timestamp
                        )
                    }
                } else {
                    lastConnectionSuccessful = false
                    Log.e(TAG, "Failed to get OAuth token")

                    // Save connection state
                    saveConnectionState()

                    PhotosConnectionResult(
                        isConnected = false,
                        status = "Token error",
                        lastTestTime = timestamp
                    )
                }
            } else {
                lastConnectionSuccessful = false
                Log.w(TAG, "Photos OAuth permission not available")

                // Save connection state
                saveConnectionState()

                PhotosConnectionResult(
                    isConnected = false,
                    status = "No permission",
                    lastTestTime = timestamp
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Photos connection test failed: ${e.message}", e)
            val timestamp = System.currentTimeMillis()
            lastConnectionTest = timestamp
            lastConnectionSuccessful = false

            // Provide more specific error status
            val errorStatus = when (e) {
                is com.google.android.gms.auth.UserRecoverableAuthException -> "Setup required"
                is com.google.android.gms.auth.GoogleAuthException -> "API not enabled"
                is IllegalStateException -> "Not initialized"
                else -> "Not available"
            }

            // Save connection state
            saveConnectionState()

            PhotosConnectionResult(
                isConnected = false,
                status = errorStatus,
                lastTestTime = timestamp
            )
        }.also {
            // Always save connection state after test
            saveConnectionState()
        }
    }

    /**
     * Get the current Photos album name
     */
    fun getCurrentAlbumName(): String = SCRIBCAL_ALBUM_NAME

    /**
     * Get the current account name for debugging
     */
    fun getCurrentAccountName(): String? = currentAccount?.name

    /**
     * Get the UserRecoverableAuthException for showing consent screen
     */
    suspend fun getUserConsentException(): com.google.android.gms.auth.UserRecoverableAuthException? = withContext(Dispatchers.IO) {
        try {
            val account = currentAccount ?: return@withContext null

            GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:$PHOTOS_SCOPE"
            )

            // If we get here, no exception was thrown
            null
        } catch (e: com.google.android.gms.auth.UserRecoverableAuthException) {
            Log.d(TAG, "Got UserRecoverableAuthException for consent screen")
            e
        } catch (e: Exception) {
            Log.w(TAG, "Got non-recoverable exception: ${e.message}")
            null
        }
    }

    /**
     * Clear cached OAuth tokens to force fresh consent
     */
    suspend fun clearCachedTokens(): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = currentAccount ?: return@withContext false

            // Try to clear cached token
            try {
                val existingToken = GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
                if (existingToken == "") {
                    Log.d(TAG, "Photos token was empty? we're clearing it anyways")
                }
                GoogleAuthUtil.clearToken(context, existingToken)
                Log.d(TAG, "Cleared cached OAuth token for Photos")
            } catch (e: Exception) {
                Log.w(TAG, "No cached token to clear or error clearing: ${e.message}")
            }

            // NOTE: We don't clear album configuration here anymore since we want to preserve
            // the user's album selection across token refreshes

            // Reset only connection test state, but keep album configuration
            lastConnectionSuccessful = false
            lastConnectionTest = null

            // Clear persistent connection state but keep account info
            prefs.edit()
                .remove(KEY_LAST_CONNECTION_TEST)
                .remove(KEY_LAST_CONNECTION_SUCCESSFUL)
                .apply()

            Log.d(TAG, "Cleared OAuth tokens and connection test state, preserved album configuration")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear cached tokens", e)
            false
        }
    }

    /**
     * Clear album configuration when we detect the selected album is invalid or deleted
     */
    suspend fun clearAlbumConfiguration(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Clear stored album configuration from database
            database.albumConfigDao().clearAlbumConfig()
            Log.d(TAG, "Cleared album configuration from database")

            // Reset album state
            scribcalAlbumReady = false
            scribcalAlbumId = null

            Log.d(TAG, "Album configuration cleared due to album-specific error")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear album configuration", e)
            false
        }
    }

    /**
     * Refresh OAuth token by clearing cached token and getting a fresh one
     * This can help resolve temporary permission issues
     */
    private suspend fun refreshOAuthToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = currentAccount ?: return@withContext false

            Log.d(TAG, "Refreshing OAuth token for Google Photos")

            // Clear existing token first
            try {
                val existingToken = GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
                if (existingToken.isNotEmpty()) {
                    GoogleAuthUtil.clearToken(context, existingToken)
                    Log.d(TAG, "Cleared existing OAuth token")
                }
            } catch (e: Exception) {
                Log.d(TAG, "No existing token to clear or error clearing: ${e.message}")
            }

            // Try to get a fresh token
            val freshToken = try {
                GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get fresh OAuth token", e)
                return@withContext false
            }

            val refreshSuccessful = freshToken.isNotEmpty()
            if (refreshSuccessful) {
                Log.d(TAG, "Successfully refreshed OAuth token")
            } else {
                Log.w(TAG, "Fresh token is empty")
            }

            refreshSuccessful

        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh OAuth token", e)
            false
        }
    }


    /**
     * Force immediate verification of the album's remote existence
     * This is useful for user-initiated actions that need to ensure the album is valid
     * before proceeding (e.g., before photo upload attempts)
     */
    suspend fun forceVerifyAlbumExists(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Force verifying album exists (user-initiated)")

            val account = currentAccount ?: run {
                Log.w(TAG, "No current account for forced album verification")
                return@withContext false
            }

            val albumConfig = database.albumConfigDao().getAlbumConfig()
            val albumId = albumConfig?.googlePhotosAlbumId

            if (albumId == null) {
                Log.w(TAG, "No album ID to verify")
                scribcalAlbumReady = false
                return@withContext false
            }

            // Try verification, with one retry attempt after token refresh if needed
            Log.d(TAG, "Starting first verification attempt for album: $albumId")
            val firstAttempt = tryVerifyAlbum(albumId)
            Log.d(TAG, "First verification attempt result: $firstAttempt")
            if (firstAttempt) {
                Log.d(TAG, "Forced verification: album still exists")
                // Update last verified time
                database.albumConfigDao().updateLastVerified(System.currentTimeMillis())
                scribcalAlbumReady = true
                return@withContext true
            }

            // First attempt failed, try token refresh and retry once
            Log.i(TAG, "Album verification failed, attempting token refresh...")
            val refreshed = refreshOAuthToken()
            Log.d(TAG, "Token refresh result: $refreshed")
            if (refreshed) {
                Log.d(TAG, "Token refreshed, retrying album verification")
                val secondAttempt = tryVerifyAlbum(albumId)
                Log.d(TAG, "Second verification attempt result: $secondAttempt")
                if (secondAttempt) {
                    Log.d(TAG, "Forced verification: album exists after token refresh")
                    // Update last verified time
                    database.albumConfigDao().updateLastVerified(System.currentTimeMillis())
                    scribcalAlbumReady = true
                    return@withContext true
                }
            } else {
                Log.w(TAG, "Token refresh failed, skipping second verification attempt")
            }

            // Both attempts failed - real access issue
            Log.w(TAG, "Forced verification: album no longer accessible even after token refresh")
            scribcalAlbumReady = false
            lastConnectionSuccessful = false

            // Clear the invalid album configuration
            clearAlbumConfiguration()

            // This is a critical error that needs user attention
            Log.e(TAG, "CRITICAL: Album access lost during upload attempt. User needs to reconfigure Google Photos.")

            return@withContext false

        } catch (e: Exception) {
            Log.e(TAG, "Forced album verification failed with exception", e)
            return@withContext false
        }
    }

    /**
     * Try to verify album exists once without retry logic
     */
    private suspend fun tryVerifyAlbum(albumId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = currentAccount ?: return@withContext false

            // Get OAuth token
            val token = try {
                GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get token for album verification", e)
                return@withContext false
            }

            if (token.isEmpty()) {
                Log.w(TAG, "Empty token for album verification")
                return@withContext false
            }

            // Verify album exists remotely
            verifyAlbumExists(token, albumId)

        } catch (e: Exception) {
            Log.w(TAG, "Album verification attempt failed", e)
            false
        }
    }

    /**
     * Get Photos connection status without testing
     */
    fun getPhotosStatus(): String {
        return when {
            !isInitialized -> "Not initialized"
            currentAccount == null -> "No account"
            !scribcalAlbumReady || scribcalAlbumId == null -> "Album not ready"
            lastConnectionTest != null -> {
                val timeAgo = getTimeAgo(lastConnectionTest!!)
                if (lastConnectionSuccessful) {
                    "Connected ($timeAgo ago)"
                } else {
                    "Failed ($timeAgo ago)"
                }
            }
            else -> "Initialized"
        }
    }

    /**
     * Get time since last connection test
     */
    fun getLastTestTime(): Long? = lastConnectionTest

    /**
     * Mark the connection as successful (for when we know it's working without full test)
     * This is used when album setup completes successfully
     */
    fun markConnectionSuccessful() {
        val currentTime = System.currentTimeMillis()
        lastConnectionTest = currentTime
        lastConnectionSuccessful = true

        // Also update the last verified time in database
        CoroutineScope(Dispatchers.IO).launch {
            try {
                database.albumConfigDao().updateLastVerified(currentTime)
                Log.d(TAG, "Marked connection as successful and updated verification time")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update last verified time", e)
            }
        }

        // Save connection state
        saveConnectionState()

        Log.d(TAG, "Connection marked as successful")
    }

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
     * Returns a Pair<hasPermission, needsUserConsent>
     */
    suspend fun checkPhotosPermission(account: Account): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking Photos permission for account: ${account.name}")

            // Try the main Photos scope first
            var token: String? = null
            var hasPermission = false

            try {
                token = GoogleAuthUtil.getToken(
                    context,
                    account,
                    "oauth2:$PHOTOS_SCOPE"
                )
                hasPermission = !token.isNullOrEmpty()
                if (hasPermission) {
                    Log.d(TAG, "Successfully obtained Photos OAuth token with full scope")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get full Photos scope: ${e.message}")
                // Don't fall back to read-only since we need full access for album creation
                throw e
            }

            Log.d(TAG, "Photos permission check result: $hasPermission")

            if (!hasPermission) {
                Log.w(TAG, "No Photos OAuth token obtained with any scope")
            }

            Pair(hasPermission, false)
        } catch (e: Exception) {
            Log.e(TAG, "Exception checking Photos permission for ${account.name}: ${e.message}", e)

            // Try to provide more specific error information
            when (e) {
                is com.google.android.gms.auth.UserRecoverableAuthException -> {
                    Log.w(TAG, "User recoverable auth exception - user needs to grant permission")
                    // This means user consent is needed
                    return@withContext Pair(false, true)
                }
                is com.google.android.gms.auth.GoogleAuthException -> {
                    Log.w(TAG, "Google auth exception: ${e.message}")
                    // Might be API not enabled or OAuth not configured
                }
                else -> {
                    Log.w(TAG, "Unknown exception type: ${e.javaClass.simpleName}")
                }
            }

            Pair(false, false)
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