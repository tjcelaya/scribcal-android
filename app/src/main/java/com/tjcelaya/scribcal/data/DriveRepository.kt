package com.tjcelaya.scribcal.data

import android.accounts.Account
import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import com.google.api.services.drive.model.Permission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DriveRepository(private val context: Context) {
    
    companion object {
        private const val TAG = "DriveRepository"
        private const val SCRIBCAL_FOLDER_NAME = "ScribCal"
    }
    
    private var driveService: Drive? = null
    private var scribcalFolderId: String? = null
    private var lastConnectionTest: Long? = null
    private var lastConnectionSuccessful = false
    
    /**
     * Initialize Google Drive service with the given account
     */
    suspend fun initializeDrive(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Initializing Drive service with account: ${account.name}")
            
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
            
            // Ensure ScribCal folder exists
            ensureScribCalFolderExists()
            
            Log.d(TAG, "Drive service initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Drive service", e)
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
     * Check if we have permission to access Drive
     */
    suspend fun checkDrivePermission(account: Account): Boolean = withContext(Dispatchers.IO) {
        try {
            val tokenNotEmpty = GoogleAuthUtil.getToken(
                context,
                account,
                "oauth2:${DriveScopes.DRIVE_FILE}"
            ) != ""

            // If we can get a token, we have permission
            Log.i(TAG, "Token fetched successfully")
            tokenNotEmpty
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check Drive permission", e)
            false
        }
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
            driveService == null -> "Not initialized"
            scribcalFolderId == null -> "No folder"
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
}

/**
 * Data class to hold Drive connection test results
 */
data class DriveConnectionResult(
    val isConnected: Boolean,
    val status: String,
    val lastTestTime: Long?
)
