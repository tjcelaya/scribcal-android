package com.tjcelaya.scribcal

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import com.tjcelaya.scribcal.databinding.ActivityMainBinding
import com.tjcelaya.scribcal.ui.main.MainFragment
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    
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
                    // Navigate to the tracking fragment and pass the photo path
                    handleSharedPhotoWithMainFragment(photoPath)
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