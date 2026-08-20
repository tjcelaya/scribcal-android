package com.tjcelaya.scribcal.data

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DriveRepository(private val context: Context) {

    companion object {
        private const val TAG = "DriveRepository"
        private const val SCRIBCAL_FOLDER_NAME = "ScribCal"
        private const val CONFIG_FILE_NAME = "scribcal_config.json"
    }

    private var driveService: Drive? = null
    private var scribcalFolderId: String? = null
    private var lastConnectionTest: Long? = null
    private var lastConnectionSuccessful = false
    private var lastConsentException: UserRecoverableAuthException? = null
    
    // Storage preferences for persistence
    private val storagePreferences = StoragePreferences(context)
    
    init {
        // Restore state from persistence on initialization
        restorePersistedState()
    }
    
    /**
     * Restore persisted state from SharedPreferences
     */
    private fun restorePersistedState() {
        try {
            // Restore basic state
            scribcalFolderId = storagePreferences.getDriveFolderId()
            lastConnectionTest = storagePreferences.getDriveLastTestTime()
            lastConnectionSuccessful = storagePreferences.wasDriveLastTestSuccessful()
            
            // If we have a persisted folder ID and account, try to restore the Drive service
            val accountName = storagePreferences.getDriveAccountName()
            if (storagePreferences.isDriveInitialized() && accountName != null && scribcalFolderId != null) {
                try {
                    val account = Account(accountName, "com.google")
                    
                    val credential = GoogleAccountCredential.usingOAuth2(
                        context,
                        listOf(DriveScopes.DRIVE_FILE)
                    )
                    credential.selectedAccount = account

                    driveService = Drive.Builder(
                        AndroidHttp.newCompatibleTransport(),
                        GsonFactory(),
                        credential
                    )
                        .setApplicationName("ScribCal")
                        .build()
                    
                    Log.d(TAG, "Restored Drive service from persistence for account: $accountName")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to restore Drive service from persistence", e)
                    // Clear invalid state
                    clearPersistedState()
                }
            }
            
            Log.d(TAG, "Drive state restored: initialized=${isDriveInitialized()}, folder=$scribcalFolderId, lastTest=$lastConnectionTest, success=$lastConnectionSuccessful")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring Drive state from persistence", e)
        }
    }
    
    /**
     * Persist current state to SharedPreferences
     */
    private fun persistCurrentState(accountName: String?) {
        try {
            storagePreferences.saveDriveState(
                initialized = isDriveInitialized(),
                folderId = scribcalFolderId,
                accountName = accountName
            )
            
            lastConnectionTest?.let { testTime ->
                storagePreferences.saveDriveTestResult(testTime, lastConnectionSuccessful)
            }
            
            Log.d(TAG, "Drive state persisted: initialized=${isDriveInitialized()}, account=$accountName")
        } catch (e: Exception) {
            Log.e(TAG, "Error persisting Drive state", e)
        }
    }
    
    /**
     * Clear persisted state
     */
    private fun clearPersistedState() {
        try {
            storagePreferences.clearDriveState()
            Log.d(TAG, "Cleared persisted Drive state")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing persisted Drive state", e)
        }
    }

    /**
     * Initialize Google Drive service with the given account
     */
    suspend fun initializeDrive(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Drive service with account: ${account.name}")

            // Clear any previous consent exception
            lastConsentException = null

            // First, check if we can get a valid OAuth token
            val tokenResult = validateDriveToken(account)
            if (!tokenResult.isValid) {
                if (tokenResult.consentException != null) {
                    Log.w(TAG, "Drive initialization requires user consent")
                    lastConsentException = tokenResult.consentException
                    return@withContext false
                } else {
                    Log.e(TAG, "Failed to validate Drive token")
                    return@withContext false
                }
            }

            val credential = GoogleAccountCredential.usingOAuth2(
                context,
                listOf(DriveScopes.DRIVE_FILE)
            )
            credential.selectedAccount = account

            driveService = Drive.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory(),
                credential
            )
                .setApplicationName("ScribCal")
                .build()

            // Now try to ensure ScribCal folder exists
            ensureScribCalFolderExists()

            // Persist successful initialization
            persistCurrentState(account.name)

            Log.d(TAG, "Drive service initialized successfully")
            true
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Drive initialization requires user consent", e)
            lastConsentException = e
            // Clear state on failure
            clearPersistedState()
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Drive service", e)
            lastConsentException = null
            // Clear state on failure
            clearPersistedState()
            false
        }
    }

    /**
     * Check if Drive service is initialized
     */
    fun isDriveInitialized(): Boolean = driveService != null && scribcalFolderId != null

    /**
     * Check if Drive service is ready for photo operations (initialized and tested successfully)
     */
    fun isDriveReady(): Boolean = isDriveInitialized() && lastConnectionSuccessful

    /**
     * Unified health check for Drive integration
     * Returns true if Drive is currently healthy and can be used for photo storage
     * This is the single source of truth for Drive health status
     */
    fun isHealthy(): Boolean {
        // Basic requirements: service must be initialized with valid folder
        if (!isDriveInitialized()) {
            return false
        }

        // Must have had at least one successful connection test
        if (!lastConnectionSuccessful) {
            return false
        }

        // If we haven't tested recently, assume healthy but trigger background verification
        if (lastConnectionTest == null) {
            return false
        }

        return true
    }

    /**
     * Create or find the ScribCal folder in Google Drive
     */
    private suspend fun ensureScribCalFolderExists() = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: throw IllegalStateException("Drive service not initialized")

            // First, check if ScribCal folder already exists
            val query = "name='$SCRIBCAL_FOLDER_NAME' and mimeType='application/vnd.google-apps.folder' and trashed=false"
            val result = drive.files().list().setQ(query).execute()

            if (result.files.isNotEmpty()) {
                scribcalFolderId = result.files[0].id
                Log.d(TAG, "Found existing ScribCal folder: $scribcalFolderId")
            } else {
                // Create ScribCal folder
                val folderMetadata = File()
                folderMetadata.name = SCRIBCAL_FOLDER_NAME
                folderMetadata.mimeType = "application/vnd.google-apps.folder"

                val folder = drive.files().create(folderMetadata).execute()
                scribcalFolderId = folder.id
                Log.d(TAG, "Created ScribCal folder: $scribcalFolderId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ensuring ScribCal folder exists", e)
            throw e
        }
    }

    /**
     * Upload a photo to the ScribCal folder and return a shareable link
     */
    suspend fun uploadPhotoAndGetLink(localFilePath: String, fileName: String): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: throw IllegalStateException("Drive service not initialized")
            val folderId = scribcalFolderId ?: throw IllegalStateException("ScribCal folder not initialized")

            Log.d(TAG, "Uploading photo: $fileName to folder: $folderId")

            // Create file metadata
            val fileMetadata = File()
            fileMetadata.name = fileName
            fileMetadata.parents = listOf(folderId)

            // Create media content
            val localFile = java.io.File(localFilePath)
            val mediaContent = FileContent("image/jpeg", localFile)

            // Upload file
            val file = drive.files().create(fileMetadata, mediaContent).execute()
            val fileId = file.id
            Log.d(TAG, "File uploaded with ID: $fileId")

            // Make file publicly readable
            val permission = Permission()
            permission.type = "anyone"
            permission.role = "reader"

            drive.permissions().create(fileId, permission).execute()
            Log.d(TAG, "File made publicly readable: $fileId")

            // Generate shareable link
            val shareableLink = "https://drive.google.com/file/d/$fileId/view"
            Log.d(TAG, "Generated shareable link: $shareableLink")

            shareableLink
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload photo and get link", e)
            null
        }
    }

    /**
     * Upload (create or overwrite) the ScribCal config JSON in the ScribCal Drive folder.
     * Returns true on success.
     */
    suspend fun uploadConfigFile(jsonContent: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: run {
                Log.w(TAG, "Drive not initialized, cannot upload config")
                return@withContext false
            }

            if (scribcalFolderId == null) {
                ensureScribCalFolderExists()
            }
            val folderId = scribcalFolderId ?: return@withContext false

            val mediaContent = ByteArrayContent("application/json", jsonContent.toByteArray(Charsets.UTF_8))

            // Look for an existing config file in the folder so we overwrite rather than duplicate
            val query = "name='$CONFIG_FILE_NAME' and '$folderId' in parents and trashed=false"
            val existing = drive.files().list().setQ(query).setSpaces("drive").execute()

            if (existing.files.isNotEmpty()) {
                val fileId = existing.files[0].id
                drive.files().update(fileId, File(), mediaContent).execute()
                Log.d(TAG, "Updated existing config file: $fileId")
            } else {
                val metadata = File()
                metadata.name = CONFIG_FILE_NAME
                metadata.parents = listOf(folderId)
                val created = drive.files().create(metadata, mediaContent).execute()
                Log.d(TAG, "Created config file: ${created.id}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to upload config file to Drive", e)
            false
        }
    }

    /**
     * Download the ScribCal config JSON from the ScribCal Drive folder, or null if absent.
     */
    suspend fun downloadConfigFile(): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: run {
                Log.w(TAG, "Drive not initialized, cannot download config")
                return@withContext null
            }

            if (scribcalFolderId == null) {
                ensureScribCalFolderExists()
            }
            val folderId = scribcalFolderId ?: return@withContext null

            val query = "name='$CONFIG_FILE_NAME' and '$folderId' in parents and trashed=false"
            val existing = drive.files().list().setQ(query).setSpaces("drive").execute()
            if (existing.files.isEmpty()) {
                Log.d(TAG, "No config file found in Drive folder")
                return@withContext null
            }

            val fileId = existing.files[0].id
            drive.files().get(fileId).executeMediaAsInputStream().use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download config file from Drive", e)
            null
        }
    }

    /**
     * Get a direct download link for viewing the image
     */
    @Deprecated("Probably being removed?")
    suspend fun getDirectImageLink(fileId: String): String? = withContext(Dispatchers.IO) {
        try {
            val drive = driveService ?: throw IllegalStateException("Drive service not initialized")

            // Get file metadata to verify it exists
            val file = drive.files().get(fileId).execute()

            // Return direct view link that works for embedding
            "https://drive.google.com/uc?id=$fileId"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get direct image link", e)
            null
        }
    }

    /**
     * Validate that we can get a valid OAuth token for Drive access
     */
    private suspend fun validateDriveToken(account: Account): DriveTokenResult = withContext(Dispatchers.IO) {
        try {
            val token = GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:${DriveScopes.DRIVE_FILE}"
            )

            if (token.isNotEmpty()) {
                Log.d(TAG, "Drive token validated successfully")
                DriveTokenResult(isValid = true, token = token, consentException = null)
            } else {
                Log.w(TAG, "Drive token is empty")
                DriveTokenResult(isValid = false, token = null, consentException = null)
            }
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "Drive token validation requires user consent", e)
            DriveTokenResult(isValid = false, token = null, consentException = e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to validate Drive token", e)
            DriveTokenResult(isValid = false, token = null, consentException = null)
        }
    }

    /**
     * Clear cached OAuth tokens for Drive access
     */
    suspend fun clearCachedTokens() = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Clearing cached Drive tokens")
            GoogleAuthUtil.clearToken(context, "oauth2:${DriveScopes.DRIVE_FILE}")
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing cached Drive tokens", e)
        }
    }

    /**
     * Check if we have permission to access Drive
     */
    suspend fun checkDrivePermission(account: Account): Boolean = withContext(Dispatchers.IO) {
        val result = validateDriveToken(account)
        result.isValid
    }

    /**
     * Test Drive connection by creating a test file
     */
    suspend fun testDriveConnection(): DriveConnectionResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val drive = driveService ?: return@withContext DriveConnectionResult(
                isConnected = false,
                status = "Drive service not initialized",
                lastTestTime = null
            )

            val folderId = scribcalFolderId ?: return@withContext DriveConnectionResult(
                isConnected = false,
                status = "ScribCal folder not found",
                lastTestTime = null
            )

            Log.d(TAG, "Testing Drive connection...")

            // Create a simple test file with timestamp
            val timestamp = System.currentTimeMillis()
            val testFileName = "connection_test_$timestamp.txt"
            val testContent = "ScribCal Drive connection test\nTimestamp: ${java.util.Date(timestamp)}"

            // Create file metadata
            val fileMetadata = File()
            fileMetadata.name = testFileName
            fileMetadata.parents = listOf(folderId)

            // Create temporary file
            val tempFile = kotlin.io.path.createTempFile("scribcal_test", ".txt").toFile()
            tempFile.writeText(testContent)

            try {
                // Upload test file
                val mediaContent = FileContent("text/plain", tempFile)
                val uploadedFile = drive.files().create(fileMetadata, mediaContent).execute()
                Log.d(TAG, "Test file uploaded: ${uploadedFile.id}")

                // Delete test file immediately
                drive.files().delete(uploadedFile.id).execute()
                Log.d(TAG, "Test file deleted successfully")

                // Store successful test time
                lastConnectionTest = timestamp
                lastConnectionSuccessful = true
                
                // Persist test results
                storagePreferences.saveDriveTestResult(timestamp, true)

                DriveConnectionResult(
                    isConnected = true,
                    status = "OK",
                    lastTestTime = timestamp
                )
            } finally {
                // Clean up temp file
                tempFile.delete()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Drive connection test failed", e)
            val timestamp = System.currentTimeMillis()
            lastConnectionTest = timestamp
            lastConnectionSuccessful = false
            
            // Persist test results
            storagePreferences.saveDriveTestResult(timestamp, false)

            DriveConnectionResult(
                isConnected = false,
                status = "Failed",
                lastTestTime = timestamp
            )
        }
    }

    /**
     * Get the current Drive folder name
     */
    fun getCurrentFolderName(): String = SCRIBCAL_FOLDER_NAME

    /**
     * Get Drive connection status without testing
     */
    fun getDriveStatus(): String {
        return when {
            lastConsentException != null -> "Setup required"
            driveService == null -> "Not initialized"
            scribcalFolderId == null -> "Setup required"
            lastConnectionTest != null -> {
                val timeAgo = getTimeAgo(lastConnectionTest!!)
                if (lastConnectionSuccessful) {
                    "Connected ($timeAgo ago)"
                } else {
                    "Failed ($timeAgo ago)"
                }
            }
            else -> "Ready"
        }
    }

    /**
     * Get time since last connection test
     */
    fun getLastTestTime(): Long? = lastConnectionTest

    /**
     * Get the stored consent exception for Drive access
     */
    fun getDriveConsentException(): UserRecoverableAuthException? = lastConsentException

    /**
     * Manually retry Drive initialization with a specific account
     */
    suspend fun retryDriveInitialization(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Manually retrying Drive initialization with account: ${account.name}")

            // Clear any previous state (both memory and persistence)
            driveService = null
            scribcalFolderId = null
            lastConnectionTest = null
            lastConnectionSuccessful = false
            lastConsentException = null
            clearPersistedState()

            // Re-initialize (which will persist state on success)
            return@withContext initializeDrive(account)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retry Drive initialization", e)
            false
        }
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
}

/**
 * Data class to hold Drive token validation results
 */
data class DriveTokenResult(
    val isValid: Boolean,
    val token: String?,
    val consentException: UserRecoverableAuthException?
)

/**
 * Data class to hold Drive connection test results
 */
data class DriveConnectionResult(
    val isConnected: Boolean,
    val status: String,
    val lastTestTime: Long?
)
