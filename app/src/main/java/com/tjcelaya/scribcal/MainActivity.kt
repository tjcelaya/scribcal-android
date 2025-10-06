package com.tjcelaya.scribcal

import android.accounts.Account
import android.accounts.AccountManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.DynamicColors
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import com.tjcelaya.scribcal.ScribCalApplication
import com.tjcelaya.scribcal.databinding.ActivityMainBinding
import com.tjcelaya.scribcal.ui.main.MainFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    // Application repositories
    private val app by lazy { application as ScribCalApplication }

    companion object {
        const val EXTRA_SHARED_PHOTO_PATH = "shared_photo_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply dynamic colors on Android 12+ if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(this)
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Handle shared photo if this activity was started by sharing
        handleSharedPhoto()
    }

    private fun handleSharedPhoto() {
        val intent = intent
        val action = intent.action
        val type = intent.type

        Log.d("MainActivity", "Intent action: $action, type: $type")

        if (Intent.ACTION_SEND == action && type != null && type.startsWith("image/")) {
            val imageUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            Log.d("MainActivity", "Received shared image: $imageUri")

            if (imageUri != null) {
                // Copy the shared image to our app's internal storage
                val photoPath = copySharedImageToStorage(imageUri)
                if (photoPath != null) {
                    Log.d("MainActivity", "Copied image to: $photoPath")
                    // Check storage preference and initialize accordingly
                    initializeStorageServiceForPhoto(photoPath)
                } else {
                    Log.e("MainActivity", "Failed to copy shared image")
                }
            }
        }
    }

    private fun copySharedImageToStorage(imageUri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(imageUri)
            if (inputStream != null) {
                // Create a unique filename
                val timestamp = System.currentTimeMillis()
                val fileName = "shared_photo_$timestamp.jpg"
                val photoFile = File(getExternalFilesDir("photos"), fileName)

                // Create directory if it doesn't exist
                photoFile.parentFile?.mkdirs()

                // Copy the image
                val outputStream = FileOutputStream(photoFile)
                inputStream.copyTo(outputStream)

                inputStream.close()
                outputStream.close()

                photoFile.absolutePath
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error copying shared image", e)
            null
        }
    }



    private fun initializeStorageServiceForPhoto(photoPath: String) {
        val storagePreferences = app.storagePreferences

        val isDriveEnabled = storagePreferences.isGoogleDriveEnabled()
        val isPhotosEnabled = storagePreferences.isGooglePhotosEnabled()

        // Use unified health check methods
        val isDriveHealthy = app.driveRepository.isHealthy()
        val isPhotosHealthy = app.photosRepository.isHealthy()

        // Check if at least one storage option is enabled and healthy
        val isStorageAvailable = (isDriveEnabled && isDriveHealthy) || (isPhotosEnabled && isPhotosHealthy)

        if (isStorageAvailable) {
            val healthyServices = mutableListOf<String>()
            if (isDriveEnabled && isDriveHealthy) healthyServices.add("Google Drive")
            if (isPhotosEnabled && isPhotosHealthy) healthyServices.add("Google Photos")

            Log.d("MainActivity", "Storage services healthy: ${healthyServices.joinToString(", ")}, proceeding with photo")
            handleSharedPhotoWithMainFragment(photoPath)
        } else {
            val enabledButNotHealthy = mutableListOf<String>()
            if (isDriveEnabled && !isDriveHealthy) enabledButNotHealthy.add("Google Drive")
            if (isPhotosEnabled && !isPhotosHealthy) enabledButNotHealthy.add("Google Photos")

            val errorMsg = if (enabledButNotHealthy.isNotEmpty()) {
                if (enabledButNotHealthy.contains("Google Photos")) {
                    "Google Photos needs album setup. Go to Settings → Google Photos → Connect to select/create an album."
                } else {
                    "${enabledButNotHealthy.joinToString(" and ")} not set up properly. Please test connections in Settings."
                }
            } else {
                "No photo storage service selected. Please choose at least one in Settings."
            }

            Log.w("MainActivity", "No storage service available: $errorMsg")
            showDetailedErrorDialog(enabledButNotHealthy, isDriveEnabled, isPhotosEnabled)
            finish()
        }
    }




    private fun handleSharedPhotoWithMainFragment(photoPath: String) {
        // Wait a bit for the navigation to be fully set up, then handle the photo
        binding.root.post {
            try {
                val navController = findNavController(R.id.nav_host_fragment_content_main)
                // First navigate to the tracking fragment if we're not already there
                navController.navigate(R.id.trackingFragment)

                // Then pass the photo path to be handled by the current fragment
                // We'll need to implement this in the TrackingFragment or MainFragment
                Log.d("MainActivity", "Navigated to tracking fragment with photo: $photoPath")

                // Store the photo path so it can be picked up by the fragment
                intent.putExtra(EXTRA_SHARED_PHOTO_PATH, photoPath)

            } catch (e: Exception) {
                Log.e("MainActivity", "Error handling shared photo", e)
            }
        }
    }


    private fun showErrorToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.e("MainActivity", "Error: $message")
    }

    private fun showDetailedErrorDialog(enabledButNotHealthy: List<String>, isDriveEnabled: Boolean, isPhotosEnabled: Boolean) {
        val title = "Photo Sharing Setup Required"
        val message = buildDetailedErrorMessage(enabledButNotHealthy, isDriveEnabled, isPhotosEnabled)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Go to Settings") { _, _ ->
                // Navigate to settings when user clicks the button
                try {
                    val navController = findNavController(R.id.nav_host_fragment_content_main)
                    navController.navigate(R.id.settingsFragment)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Error navigating to settings", e)
                    // Fallback: just finish the activity
                    finish()
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun buildDetailedErrorMessage(enabledButNotHealthy: List<String>, isDriveEnabled: Boolean, isPhotosEnabled: Boolean): String {
        val sb = StringBuilder()

        sb.append("To share photos to ScribCal, you need at least one photo storage service properly configured.\n\n")

        if (enabledButNotHealthy.isNotEmpty()) {
            sb.append("Issues found:\n")

            if (enabledButNotHealthy.contains("Google Photos")) {
                sb.append("\u2022 Google Photos: No album selected for storing ScribCal photos\n")
                sb.append("  → Go to Settings → Google Photos → Enter album name → Click Connect\n")
                sb.append("  → Choose to create the album when prompted\n\n")
            }

            if (enabledButNotHealthy.contains("Google Drive")) {
                sb.append("\u2022 Google Drive: Connection not properly established\n")
                sb.append("  → Go to Settings → Google Drive → Click Connect\n")
                sb.append("  → Grant necessary permissions when prompted\n\n")
            }
        } else {
            sb.append("No photo storage services are currently enabled.\n\n")
            sb.append("Available options:\n")
            sb.append("\u2022 Google Photos: Store photos in a Google Photos album\n")
            sb.append("\u2022 Google Drive: Store photos in your Google Drive\n\n")
            sb.append("Go to Settings to enable and configure at least one option.\n")
        }

        sb.append("After setup is complete, try sharing the image again.")

        return sb.toString()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                findNavController(R.id.nav_host_fragment_content_main)
                    .navigate(R.id.settingsFragment)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}