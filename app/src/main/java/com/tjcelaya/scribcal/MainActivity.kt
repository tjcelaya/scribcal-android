package com.tjcelaya.scribcal

import android.accounts.Account
import android.accounts.AccountManager
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        val isDriveReady = app.driveRepository.isDriveReady()
        val isPhotosReady = app.photosRepository.isPhotosReady()
        
        // Check if at least one storage option is enabled and ready
        val isStorageAvailable = (isDriveEnabled && isDriveReady) || (isPhotosEnabled && isPhotosReady)
        
        if (isStorageAvailable) {
            val readyServices = mutableListOf<String>()
            if (isDriveEnabled && isDriveReady) readyServices.add("Google Drive")
            if (isPhotosEnabled && isPhotosReady) readyServices.add("Google Photos")
            
            Log.d("MainActivity", "Storage services ready: ${readyServices.joinToString(", ")}, proceeding with photo")
            handleSharedPhotoWithMainFragment(photoPath)
        } else {
            val enabledButNotReady = mutableListOf<String>()
            if (isDriveEnabled && !isDriveReady) enabledButNotReady.add("Google Drive")
            if (isPhotosEnabled && !isPhotosReady) enabledButNotReady.add("Google Photos")
            
            val errorMsg = if (enabledButNotReady.isNotEmpty()) {
                "${enabledButNotReady.joinToString(" and ")} not set up. Please configure in Settings."
            } else {
                "No photo storage service selected. Please choose at least one in Settings."
            }
            
            Log.w("MainActivity", "No storage service available: $errorMsg")
            showErrorToast(errorMsg)
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