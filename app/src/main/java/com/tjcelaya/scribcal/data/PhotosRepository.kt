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
    
    // Storage preferences for centralized persistence
    private val storagePreferences = StoragePreferences(context)

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
        
        // HTTP response codes that indicate auth issues warranting token refresh
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
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
     * Load persisted connection state from centralized StoragePreferences
     */
    private fun loadPersistedState() {
        try {
            // Load connection test state from centralized storage
            lastConnectionTest = storagePreferences.getPhotosLastTestTime()
            lastConnectionSuccessful = storagePreferences.wasPhotosLastTestSuccessful()

            // Load account info and initialization state
            val accountName = storagePreferences.getPhotosAccountName()
            isInitialized = storagePreferences.isPhotosInitialized()
            
            if (accountName != null && isInitialized) {
                currentAccount = Account(accountName, "com.google")
            }

            Log.d(TAG, "Loaded persisted state - initialized: $isInitialized, lastTest: $lastConnectionTest, successful: $lastConnectionSuccessful, account: $accountName")

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
     * Save connection state to centralized StoragePreferences
     */
    private fun saveConnectionState() {
        try {
            // Save initialization and account state
            storagePreferences.savePhotosState(
                initialized = isInitialized,
                accountName = currentAccount?.name
            )
            
            // Save connection test results
            if (lastConnectionTest != null) {
                storagePreferences.savePhotosTestResult(lastConnectionTest!!, lastConnectionSuccessful)
            }

            Log.d(TAG, "Saved connection state - initialized: $isInitialized, lastTest: $lastConnectionTest, successful: $lastConnectionSuccessful")

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
                
                // Persist successful initialization
                saveConnectionState()

                Log.d(TAG, "Photos service initialized successfully with permission validation")
                true
            } else if (needsUserConsent) {
                // Still set up the account but mark as needing consent
                currentAccount = account
                isInitialized = true
                scribcalAlbumReady = false // Not ready until consent is granted
                
                // Persist partial initialization
                saveConnectionState()

                Log.d(TAG, "Photos service initialized but user consent required")
                true // Return true so UI can show consent screen
            } else {
                Log.w(TAG, "Photos permission not available for account: ${account.name}")
                // Clear state on failure
                storagePreferences.clearPhotosState()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Photos service", e)
            currentAccount = null
            isInitialized = false
            scribcalAlbumReady = false
            // Clear state on exception
            storagePreferences.clearPhotosState()
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
     * Generic retry wrapper for Google Photos API calls that automatically handles
     * 403/401 errors with token refresh and retry logic
     * 
     * @param operationName Human-readable name for logging (e.g., "album creation", "photo upload")
     * @param apiCall Suspend function that takes an access token and returns T?, where null indicates failure
     * @return Result of the API call, or null if all attempts failed
     */
    private suspend fun <T> executeWithTokenRetry(
        operationName: String,
        apiCall: suspend (accessToken: String) -> T?
    ): T? = withContext(Dispatchers.IO) {
        val account = currentAccount ?: run {
            Log.e(TAG, "Cannot execute $operationName: no current account set")
            return@withContext null
        }
        
        // Get initial token
        val initialToken = try {
            GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get initial OAuth token for $operationName", e)
            return@withContext null
        }
        
        if (initialToken.isEmpty()) {
            Log.e(TAG, "Initial OAuth token is empty for $operationName")
            return@withContext null
        }
        
        // First attempt
        Log.d(TAG, "Executing $operationName with initial token...")
        val firstResult = apiCall(initialToken)
        if (firstResult != null) {
            Log.d(TAG, "$operationName succeeded on first attempt")
            return@withContext firstResult
        }
        
        // First attempt failed, try token refresh and retry once
        Log.i(TAG, "$operationName failed on first attempt, trying token refresh...")
        val refreshed = refreshOAuthToken()
        if (!refreshed) {
            Log.e(TAG, "Token refresh failed for $operationName, aborting retry")
            return@withContext null
        }
        
        // Get fresh token for retry
        val freshToken = try {
            GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get fresh OAuth token for $operationName retry", e)
            return@withContext null
        }
        
        if (freshToken.isEmpty()) {
            Log.e(TAG, "Fresh OAuth token is empty for $operationName retry")
            return@withContext null
        }
        
        // Second attempt with fresh token
        Log.d(TAG, "Retrying $operationName with refreshed token...")
        val secondResult = apiCall(freshToken)
        if (secondResult != null) {
            Log.i(TAG, "$operationName succeeded after token refresh")
        } else {
            Log.e(TAG, "$operationName failed even after token refresh")
        }
        
        secondResult
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
     * Verify that an album still exists in Google Photos with automatic retry on auth errors
     */
    private suspend fun verifyAlbumExists(accessToken: String, albumId: String): Boolean = withContext(Dispatchers.IO) {
        // First attempt with provided token
        val firstAttempt = attemptVerifyAlbumExists(accessToken, albumId)
        if (firstAttempt != null) {
            return@withContext firstAttempt
        }
        
        // If first attempt failed with auth error, try token refresh and retry once
        Log.i(TAG, "Album verification failed with auth error, attempting token refresh and retry...")
        val refreshed = refreshOAuthToken()
        if (refreshed) {
            Log.d(TAG, "Token refreshed, retrying album verification...")
            // Get fresh token and retry
            val account = currentAccount ?: return@withContext false
            val freshToken = try {
                GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get fresh token after refresh for album verification", e)
                return@withContext false
            }
            
            if (freshToken.isNotEmpty()) {
                val secondAttempt = attemptVerifyAlbumExists(freshToken, albumId)
                return@withContext secondAttempt ?: false
            }
        }
        
        Log.e(TAG, "Album verification failed even after token refresh attempt")
        false
    }
    
    /**
     * Single attempt to verify album exists without retry logic
     * Returns: true if album exists, false if album doesn't exist, null if auth error occurred
     */
    private suspend fun attemptVerifyAlbumExists(accessToken: String, albumId: String): Boolean? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting to verify album exists: $albumId")
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
                    Log.w(TAG, "Album access forbidden (permissions issue): $albumId - will attempt token refresh")
                    null // Indicates auth error that should trigger retry
                }
                401 -> {
                    Log.w(TAG, "Album access unauthorized (token expired): $albumId - will attempt token refresh")
                    null // Indicates auth error that should trigger retry
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
     * Create the ScribCal album with automatic retry on 403/401 errors
     */
    private suspend fun createScribCalAlbum(accessToken: String): String? = withContext(Dispatchers.IO) {
        // First attempt with provided token
        val firstAttempt = attemptCreateScribCalAlbum(accessToken)
        if (firstAttempt != null) {
            return@withContext firstAttempt
        }
        
        // If first attempt failed with 403/401, try token refresh and retry once
        Log.i(TAG, "Album creation failed, attempting token refresh and retry...")
        val refreshed = refreshOAuthToken()
        if (refreshed) {
            Log.d(TAG, "Token refreshed, retrying album creation...")
            // Get fresh token and retry
            val account = currentAccount ?: return@withContext null
            val freshToken = try {
                GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get fresh token after refresh", e)
                return@withContext null
            }
            
            if (freshToken.isNotEmpty()) {
                return@withContext attemptCreateScribCalAlbum(freshToken)
            }
        }
        
        Log.e(TAG, "Album creation failed even after token refresh attempt")
        null
    }
    
    /**
     * Single attempt to create ScribCal album without retry logic
     */
    private suspend fun attemptCreateScribCalAlbum(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Attempting ScribCal album creation: $SCRIBCAL_ALBUM_NAME")
            
            // Log comprehensive request details
            val endpoint = "https://photoslibrary.googleapis.com/v1/albums"
            Log.d(TAG, "🔗 API Endpoint: $endpoint")
            Log.d(TAG, "🔑 Token (first 20 chars): ${accessToken.take(20)}...")
            Log.d(TAG, "🔑 Token (last 20 chars): ...${accessToken.takeLast(20)}")
            Log.d(TAG, "📏 Token length: ${accessToken.length}")
            
            val url = URL(endpoint)
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
            
            Log.d(TAG, "🌐 HTTP Method: POST")
            Log.d(TAG, "📋 Request Headers:")
            Log.d(TAG, "   Authorization: Bearer ${accessToken.take(20)}...")
            Log.d(TAG, "   Content-Type: application/json")
            Log.d(TAG, "📄 Request payload: ${requestJson.toString()}")

            connection.outputStream.use { outputStream ->
                outputStream.write(requestJson.toString().toByteArray())
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "Album creation response code: $responseCode")
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "Album creation success response: $response")
                val responseJson = JSONObject(response)
                val albumId = responseJson.getString("id")
                Log.d(TAG, "Created ScribCal album: $albumId")
                albumId
            } else {
                val errorStream = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "Album creation failed with HTTP $responseCode")
                Log.e(TAG, "Error response: $errorStream")
                
                // Provide specific error guidance based on common response codes
                when (responseCode) {
                    403 -> {
                        Log.e(TAG, "ERROR 403: Google Photos API not enabled or insufficient permissions.")
                        Log.e(TAG, "This could indicate: 1) OAuth token has insufficient scope, 2) API not enabled in Google Cloud Console, 3) API quota exceeded")
                    }
                    401 -> {
                        Log.e(TAG, "ERROR 401: OAuth token invalid or expired. Will attempt token refresh.")
                    }
                    400 -> Log.e(TAG, "ERROR 400: Malformed request. Album title might be invalid: '$SCRIBCAL_ALBUM_NAME'")
                    429 -> Log.e(TAG, "ERROR 429: Rate limit exceeded. Too many API requests. Wait and retry.")
                    404 -> Log.e(TAG, "ERROR 404: Google Photos Library API endpoint not found. API might not be enabled in Google Cloud Console.")
                    else -> Log.e(TAG, "ERROR $responseCode: Unexpected error creating album. Check network connection and Google Cloud Console API setup.")
                }
                
                // Return null to indicate failure - caller will handle retry logic
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating ScribCal album", e)
            when (e) {
                is java.net.UnknownHostException -> Log.e(TAG, "Network error: No internet connection or DNS resolution failed")
                is java.net.ConnectException -> Log.e(TAG, "Network error: Could not connect to Google Photos API")
                is javax.net.ssl.SSLException -> Log.e(TAG, "SSL error: Certificate or secure connection issue")
                is java.io.IOException -> Log.e(TAG, "IO error: Network or stream issue during album creation")
                else -> Log.e(TAG, "Unexpected error type during album creation: ${e.javaClass.simpleName}")
            }
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
     * This can help resolve temporary permission issues including 403/401 errors
     */
    private suspend fun refreshOAuthToken(): Boolean = withContext(Dispatchers.IO) {
        try {
            val account = currentAccount ?: run {
                Log.e(TAG, "Cannot refresh OAuth token: no current account set")
                return@withContext false
            }

            Log.i(TAG, "Refreshing OAuth token for Google Photos account: ${account.name}")
            val startTime = System.currentTimeMillis()

            // Clear existing token first
            var clearedToken: String? = null
            try {
                clearedToken = GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
                if (clearedToken.isNotEmpty()) {
                    GoogleAuthUtil.clearToken(context, clearedToken)
                    Log.d(TAG, "Cleared existing OAuth token (${clearedToken.take(20)}...)")
                } else {
                    Log.d(TAG, "Existing token was already empty")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error getting/clearing existing token (this may be normal): ${e.message}")
            }

            // Wait a moment for token invalidation to propagate
            kotlinx.coroutines.delay(100)

            // Try to get a fresh token
            val freshToken = try {
                Log.d(TAG, "Requesting fresh OAuth token with full Photos scope...")
                GoogleAuthUtil.getToken(context, account, "oauth2:$PHOTOS_SCOPE")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get fresh OAuth token: ${e.javaClass.simpleName}: ${e.message}")
                when (e) {
                    is com.google.android.gms.auth.UserRecoverableAuthException -> {
                        Log.e(TAG, "User consent required for token refresh - app needs to handle this")
                    }
                    is com.google.android.gms.auth.GoogleAuthException -> {
                        Log.e(TAG, "Google Auth error during token refresh - may indicate API/OAuth configuration issue")
                    }
                    else -> {
                        Log.e(TAG, "Unexpected error type during token refresh")
                    }
                }
                return@withContext false
            }

            val refreshTime = System.currentTimeMillis() - startTime
            val refreshSuccessful = freshToken.isNotEmpty()
            
            if (refreshSuccessful) {
                Log.i(TAG, "Successfully refreshed OAuth token in ${refreshTime}ms (${freshToken.take(20)}...)")
                // Verify the token has the required scope
                if (clearedToken != freshToken) {
                    Log.d(TAG, "✅ Fresh token is different from cleared token - refresh successful")
                } else {
                    Log.w(TAG, "⚠️ Fresh token is identical to cleared token - may indicate caching issue")
                }
            } else {
                Log.e(TAG, "❌ Fresh token is empty after refresh attempt in ${refreshTime}ms")
            }

            refreshSuccessful

        } catch (e: Exception) {
            Log.e(TAG, "Exception during OAuth token refresh: ${e.javaClass.simpleName}: ${e.message}", e)
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
     * Debug what scopes are actually included in the OAuth token
     * This calls Google's tokeninfo endpoint to see what permissions we actually have
     */
    suspend fun debugTokenScopes(token: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔍 Debugging token scopes...")
            val endpoint = "https://www.googleapis.com/oauth2/v1/tokeninfo?access_token=$token"
            
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            
            val responseCode = connection.responseCode
            Log.d(TAG, "🔍 Token info response code: $responseCode")
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                Log.d(TAG, "🔍 Token info response: $response")
                
                val tokenInfo = JSONObject(response)
                if (tokenInfo.has("scope")) {
                    val scopeString = tokenInfo.getString("scope")
                    Log.d(TAG, "🔍 Token scopes: $scopeString")
                    
                    val requiredScope = "https://www.googleapis.com/auth/photoslibrary"
                    val hasRequiredScope = scopeString.contains(requiredScope)
                    Log.d(TAG, "🔍 Has required Photos scope ($requiredScope): $hasRequiredScope")
                    
                    if (!hasRequiredScope) {
                        Log.e(TAG, "❌ TOKEN SCOPE MISMATCH: Required scope '$requiredScope' not found in token")
                        Log.e(TAG, "❌ Available scopes: $scopeString")
                        Log.e(TAG, "❌ This confirms the OAuth consent screen or client setup is missing the Photos Library API scope")
                    } else {
                        Log.d(TAG, "✅ Token includes required Photos scope")
                    }
                    
                    return@withContext "Scopes: $scopeString (has Photos: $hasRequiredScope)"
                } else {
                    Log.w(TAG, "🔍 No scope information in token response")
                    return@withContext "No scope info in response: $response"
                }
            } else {
                val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e(TAG, "🔍 Token info request failed: $responseCode - $errorResponse")
                return@withContext "Token info failed: $responseCode"
            }
        } catch (e: Exception) {
            Log.e(TAG, "🔍 Exception debugging token scopes", e)
            return@withContext "Exception: ${e.message}"
        }
    }
    
    /**
     * Test basic Google Photos API availability by making a simple API call
     * This helps diagnose if the API is enabled and accessible
     */
    suspend fun testPhotosAPIAvailability(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Testing Google Photos API availability...")
            
            // First, debug what scopes this token actually has
            val scopeDebugResult = debugTokenScopes(token)
            Log.i(TAG, "🔍 Token scope analysis: $scopeDebugResult")
            
            // Log comprehensive request details
            val endpoint = "https://photoslibrary.googleapis.com/v1/albums?pageSize=1"
            Log.d(TAG, "🔗 API Endpoint: $endpoint")
            Log.d(TAG, "🔑 Token (first 20 chars): ${token.take(20)}...")
            Log.d(TAG, "🔑 Token (last 20 chars): ...${token.takeLast(20)}")
            Log.d(TAG, "📏 Token length: ${token.length}")
            
            // Make a simple API call to list albums (without actually reading results)
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Content-Type", "application/json")
            
            Log.d(TAG, "🌐 HTTP Method: GET")
            Log.d(TAG, "📋 Request Headers:")
            Log.d(TAG, "   Authorization: Bearer ${token.take(20)}...")
            Log.d(TAG, "   Content-Type: application/json")

            val responseCode = connection.responseCode
            Log.d(TAG, "📨 API availability test response code: $responseCode")
            
            when (responseCode) {
                200 -> {
                    Log.d(TAG, "✅ Google Photos API is available and accessible")
                    true
                }
                403 -> {
                    Log.e(TAG, "❌ Google Photos API: 403 Forbidden - API not enabled or insufficient permissions")
                    val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e(TAG, "Error details: $errorResponse")
                    false
                }
                401 -> {
                    Log.e(TAG, "❌ Google Photos API: 401 Unauthorized - Invalid or expired OAuth token")
                    false
                }
                404 -> {
                    Log.e(TAG, "❌ Google Photos API: 404 Not Found - API endpoint not available")
                    false
                }
                else -> {
                    val errorResponse = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    Log.e(TAG, "❌ Google Photos API: HTTP $responseCode - Unexpected error")
                    Log.e(TAG, "Error details: $errorResponse")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception testing Google Photos API availability", e)
            when (e) {
                is java.net.UnknownHostException -> Log.e(TAG, "Network error: Cannot resolve photoslibrary.googleapis.com")
                is java.net.ConnectException -> Log.e(TAG, "Network error: Cannot connect to Google Photos API servers")
                is javax.net.ssl.SSLException -> Log.e(TAG, "SSL error: Secure connection issue")
                else -> Log.e(TAG, "Unexpected error: ${e.message}")
            }
            false
        }
    }
    
    /**
     * Test different scope request formats to debug scope issues
     */
    suspend fun debugScopeRequests(account: Account): String = withContext(Dispatchers.IO) {
        val results = mutableListOf<String>()
        
        val scopeVariants = listOf(
            "oauth2:$PHOTOS_SCOPE",
            "oauth2:https://www.googleapis.com/auth/photoslibrary", 
            "oauth2:photoslibrary",
            PHOTOS_SCOPE,
            "https://www.googleapis.com/auth/photoslibrary"
        )
        
        for ((index, scope) in scopeVariants.withIndex()) {
            try {
                Log.d(TAG, "🧪 Testing scope variant #${index + 1}: '$scope'")
                val token = GoogleAuthUtil.getToken(context, account, scope)
                if (token.isNotEmpty()) {
                    results.add("✅ Scope #${index + 1} '$scope': SUCCESS (token length ${token.length})")
                    // Check what scopes this token actually has
                    val scopeInfo = debugTokenScopes(token)
                    results.add("   → Token scopes: $scopeInfo")
                } else {
                    results.add("❌ Scope #${index + 1} '$scope': Empty token")
                }
            } catch (e: Exception) {
                results.add("❌ Scope #${index + 1} '$scope': ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        
        return@withContext results.joinToString("\n")
    }
    
    /**
     * Check if we have permission to access Photos
     * Returns a Pair<hasPermission, needsUserConsent>
     */
    suspend fun checkPhotosPermission(account: Account): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Checking Photos permission for account: ${account.name}")
            
            // First, run our comprehensive scope debugging
            val scopeDebugResults = debugScopeRequests(account)
            Log.i(TAG, "🧪 COMPREHENSIVE SCOPE DEBUG RESULTS:\n$scopeDebugResults")

            // Try the main Photos scope first
            var token: String? = null
            var hasPermission = false

            try {
                Log.d(TAG, "🎯 Primary scope request: oauth2:$PHOTOS_SCOPE")
                token = GoogleAuthUtil.getToken(
                    context,
                    account,
                    "oauth2:$PHOTOS_SCOPE"
                )
                hasPermission = !token.isNullOrEmpty()
                if (hasPermission) {
                    Log.d(TAG, "✅ Successfully obtained Photos OAuth token with full scope")
                    // Immediately debug what scopes are actually in this token
                    val actualScopes = debugTokenScopes(token!!)
                    Log.i(TAG, "🔍 PRIMARY TOKEN SCOPE ANALYSIS: $actualScopes")
                } else {
                    Log.w(TAG, "❌ Primary scope request returned empty token")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to get primary Photos scope: ${e.javaClass.simpleName}: ${e.message}", e)
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