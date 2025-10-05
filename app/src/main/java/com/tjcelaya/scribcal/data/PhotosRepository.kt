package com.tjcelaya.scribcal.data

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.gson.GsonFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import org.json.JSONArray
import java.io.RandomAccessFile

class PhotosRepository(private val context: Context) {

    companion object {
        private const val TAG = "PhotosRepository"
        private const val SCRIBCAL_ALBUM_NAME = "ScribCal Events"
        private const val PHOTOS_SCOPE = "https://www.googleapis.com/auth/photoslibrary"
        private const val PHOTOS_READONLY_SCOPE = "https://www.googleapis.com/auth/photoslibrary.readonly"
    }

    private var currentAccount: Account? = null
    private var isInitialized = false
    private var scribcalAlbumReady = false
    private var scribcalAlbumId: String? = null
    private var lastConnectionTest: Long? = null
    private var lastConnectionSuccessful = false

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
     */
    fun isPhotosReady(): Boolean = isPhotosInitialized() && scribcalAlbumReady && lastConnectionSuccessful

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
     * Upload photo using Google Photos REST API
     */
    private suspend fun uploadPhotoViaRestApi(
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
            
            if (token == null) {
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
            
            // Step 2: Create media item in Google Photos
            val mediaItemId = createMediaItem(uploadToken, fileName, token)
            if (mediaItemId == null) {
                Log.e(TAG, "Failed to create media item")
                return@withContext null
            }
            
            progressCallback?.invoke(85)
            
            // Step 3: Add to ScribCal album
            val success = addToScribCalAlbum(mediaItemId, token)
            if (!success) {
                Log.w(TAG, "Failed to add to ScribCal album, but photo was uploaded")
            }
            
            progressCallback?.invoke(95)
            
            // Return a Google Photos URL
            val photoUrl = "https://photos.google.com/lr/photo/$mediaItemId"
            Log.d(TAG, "Successfully uploaded photo to Google Photos: $photoUrl")
            
            photoUrl
            
        } catch (e: Exception) {
            Log.e(TAG, "REST API upload failed", e)
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
     * Create media item from upload token
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
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(response)
                val results = responseJson.getJSONArray("newMediaItemResults")
                
                if (results.length() > 0) {
                    val result = results.getJSONObject(0)
                    val status = result.getJSONObject("status")
                    if (status.getInt("code") == 0) {
                        val mediaItem = result.getJSONObject("mediaItem")
                        val mediaItemId = mediaItem.getString("id")
                        Log.d(TAG, "Media item created: $mediaItemId")
                        return@withContext mediaItemId
                    } else {
                        Log.e(TAG, "Media item creation failed: ${status.getString("message")}")
                    }
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
     * Find or create the ScribCal album
     */
    private suspend fun findOrCreateScribCalAlbum(accessToken: String): String? = withContext(Dispatchers.IO) {
        try {
            // First try to find existing album
            val url = URL("https://photoslibrary.googleapis.com/v1/albums")
            val connection = url.openConnection() as HttpURLConnection
            
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            
            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val responseJson = JSONObject(response)
                val albums = responseJson.optJSONArray("albums")
                
                if (albums != null) {
                    for (i in 0 until albums.length()) {
                        val album = albums.getJSONObject(i)
                        if (album.getString("title") == SCRIBCAL_ALBUM_NAME) {
                            val albumId = album.getString("id")
                            Log.d(TAG, "Found existing ScribCal album: $albumId")
                            return@withContext albumId
                        }
                    }
                }
            }
            
            // Album doesn't exist, create it
            return@withContext createScribCalAlbum(accessToken)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find ScribCal album", e)
            null
        }
    }
    
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
     * Test Photos connection by validating OAuth permissions and creating album
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
                
                if (token != null) {
                    // Test API access and create/find ScribCal album
                    val albumId = findOrCreateScribCalAlbum(token)
                    if (albumId != null) {
                        lastConnectionSuccessful = true
                        scribcalAlbumReady = true
                        scribcalAlbumId = albumId
                        Log.d(TAG, "Photos connection test successful - album ready: $albumId")
                        
                        PhotosConnectionResult(
                            isConnected = true,
                            status = "OK",
                            lastTestTime = timestamp
                        )
                    } else {
                        lastConnectionSuccessful = false
                        scribcalAlbumReady = false
                        scribcalAlbumId = null
                        Log.e(TAG, "Failed to create/access ScribCal album")
                        
                        PhotosConnectionResult(
                            isConnected = false,
                            status = "Album creation failed",
                            lastTestTime = timestamp
                        )
                    }
                } else {
                    lastConnectionSuccessful = false
                    Log.e(TAG, "Failed to get OAuth token")
                    
                    PhotosConnectionResult(
                        isConnected = false,
                        status = "Token error",
                        lastTestTime = timestamp
                    )
                }
            } else {
                lastConnectionSuccessful = false
                Log.w(TAG, "Photos OAuth permission not available")
                
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

            PhotosConnectionResult(
                isConnected = false,
                status = errorStatus,
                lastTestTime = timestamp
            )
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
                Log.w(TAG, "Failed to get full Photos scope, trying read-only: ${e.message}")
                
                // Try read-only scope as fallback
                try {
                    token = GoogleAuthUtil.getToken(
                        context,
                        account,
                        "oauth2:$PHOTOS_READONLY_SCOPE"
                    )
                    hasPermission = !token.isNullOrEmpty()
                    if (hasPermission) {
                        Log.d(TAG, "Successfully obtained Photos OAuth token with read-only scope")
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "Failed to get read-only Photos scope: ${e2.message}")
                    throw e // Re-throw original exception
                }
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